"""Safe media display helpers for structured facts received from Core."""

from urllib.parse import urlsplit


def image_url(value):
    if not isinstance(value, str) or len(value) > 4000:
        return None
    try:
        url = urlsplit(value)
        host = url.hostname or ""
        allowed = any(
            host == domain or host.endswith("." + domain)
            for domain in ("pddpic.com", "pinduoduo.com", "yangkeduo.com")
        )
        return (
            value
            if url.scheme == "https"
            and allowed
            and not url.username
            and not url.password
            and not url.port
            else None
        )
    except ValueError:
        return None
