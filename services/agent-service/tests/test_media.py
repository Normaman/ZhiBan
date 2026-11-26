import pytest
from copilot.agent.media import image_url


@pytest.mark.parametrize(
    "url",
    [
        "https://img.pddpic.com/a.png",
        "https://cdn.pinduoduo.com/a.png",
        "https://img.yangkeduo.com/a.png",
    ],
)
def test_safe_https_media_sources_are_preserved(url):
    assert image_url(url) == url


@pytest.mark.parametrize(
    "url",
    [
        "javascript:alert(1)",
        "https://img.pddpic.com.evil.example/x.jpg",
        "http://127.0.0.1/private",
        "https://img.pddpic.com@evil.example/x.jpg",
        "https://img.pddpic.com:8443/x.jpg",
        "https://:password@img.pddpic.com/x.jpg",
        "x" * 4001,
    ],
)
def test_unsafe_media_sources_are_rejected(url):
    assert image_url(url) is None
