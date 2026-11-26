"""Prepare a scoped sending grant for one explicitly selected test conversation.

This does not send a message. Restart services to load the grant, then submit the
exact approved text from the workbench. Timed grants expire in both services; persistent grants require explicit revocation.
"""

import argparse
import json
import os
import time
import urllib.request

from dev import RUNTIME, environment


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--conversation", help="Internal conversation ID of the authorized test buyer"
    )
    duration = parser.add_mutually_exclusive_group()
    duration.add_argument("--minutes", type=int, default=30, choices=range(1, 61), metavar="1..60")
    duration.add_argument(
        "--persistent", action="store_true", help="Keep this conversation authorized until revoked"
    )
    parser.add_argument("--confirm-live-send", action="store_true")
    parser.add_argument("--disable", action="store_true")
    args = parser.parse_args()
    target = RUNTIME / "live-send-scope.json"
    if args.disable:
        target.unlink(missing_ok=True)
        print("Grant removed. Restart services to disable the currently loaded grant immediately.")
        return
    if not args.conversation or not args.confirm_live_send:
        parser.error("Specify the authorized test conversation and --confirm-live-send")
    env = environment()
    from browser_session import authenticated_opener
    opener = authenticated_opener("http://127.0.0.1:8080", env["REVIEWER_USER"], env["REVIEWER_PASSWORD"])
    from urllib.parse import quote

    request = urllib.request.Request(
        "http://127.0.0.1:8080/api/v1/conversations/" + quote(args.conversation, safe=""),
        headers={"X-Shop-Id": env["SHOP_ID"]},
    )
    try:
        with opener.open(request, timeout=15) as response:
            detail = json.load(response)
    except Exception:
        raise SystemExit("Could not verify this conversation belongs to the current shop") from None
    if detail.get("id") != args.conversation or not any(
        m.get("direction") == "inbound" and m.get("message_type") != "system_hint"
        for m in detail.get("messages", [])
    ):
        raise SystemExit("A verified buyer consultation is required")
    grant = {
        "shop_id": env["SHOP_ID"],
        "conversation_id": args.conversation,
        "expires_at": -1 if args.persistent else int(time.time() * 1000) + args.minutes * 60000,
        "persistent": args.persistent,
    }
    with os.fdopen(os.open(target, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as stream:
        json.dump(grant, stream)
    target.chmod(0o600)
    print(
        "One conversation granted "
        + ("until revoked" if args.persistent else f"for {args.minutes} minutes")
        + ". Restart services; no message has been sent."
    )


if __name__ == "__main__":
    main()
