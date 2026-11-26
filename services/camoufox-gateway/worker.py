"""One disposable launcher process, replaced by Node after preparing Camoufox options."""

import json
import os
import sys
from pathlib import Path


def main() -> None:
    # Options/credentials travel on stdin and a private temporary file, never argv/logs.
    from camoufox.addons import DefaultAddons
    from camoufox.utils import launch_options
    from playwright._impl._driver import compute_driver_executable

    request = json.loads(sys.stdin.buffer.readline())
    options = launch_options(
        os="windows",
        headless=True,
        geoip=bool(request.get("proxy")),
        proxy=request.get("proxy"),
        exclude_addons=list(DefaultAddons),
    )
    options["env"]["CAMOUFOX_INSTANCE_ID"] = os.environ["CAMOUFOX_INSTANCE_ID"]
    options = {
        key.split("_")[0] + "".join(part.capitalize() for part in key.split("_")[1:]): value
        for key, value in options.items()
    }
    options.update(host="127.0.0.1", port=0, timeout=35000)
    directory = Path(sys.argv[1])
    config = directory / "launch.json"
    descriptor = os.open(config, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as stream:
        json.dump(options, stream, default=str)
    node = str(compute_driver_executable()[0])
    launcher = Path(__file__).with_name("browser-server.cjs")
    # exec keeps the same supervised PID. Node holds BrowserServer, not a detached
    # Python Playwright object whose lifetime belongs to another request thread.
    os.execv(node, [node, str(launcher), str(Path(node).parent / "package"), str(config)])


if __name__ == "__main__":
    try:
        main()
    except Exception:
        # Camoufox exceptions can include full proxy credentials and launch options.
        print("GATEWAY_START_FAILED", file=sys.stderr, flush=True)
        sys.exit(1)
