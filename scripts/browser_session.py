"""Authenticate maintenance scripts through the same Cookie and CSRF flow as the UI."""

import http.cookiejar
import json
import urllib.request


def authenticated_opener(base_url, username, password):
    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(jar)
    )
    with opener.open(base_url + "/api/v1/auth/session", timeout=10) as response:
        csrf = json.load(response)
    request = urllib.request.Request(
        base_url + "/api/v1/auth/login",
        data=json.dumps({"username": username, "password": password}).encode(),
        headers={"Content-Type": "application/json", csrf["csrf_header"]: csrf["csrf_token"]},
    )
    with opener.open(request, timeout=15) as response:
        json.load(response)
    return opener
