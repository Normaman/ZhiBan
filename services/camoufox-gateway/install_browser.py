"""Install a fixed browser release with the pinned Camoufox package manager."""

import os
import re
import shutil
import sys
import time
from pathlib import Path

import requests
from camoufox.locale import MMDB_FILE
from camoufox.pkgman import CamoufoxFetcher, Version


class PinnedFetcher(CamoufoxFetcher):
    def fetch_latest(self) -> None:
        # Fixed release URLs avoid the unauthenticated GitHub API rate limit.
        tag = os.environ["CAMOUFOX_BROWSER_VERSION"]
        if not re.fullmatch(r"[0-9.]+-beta\.[0-9]+", tag):
            raise ValueError("Invalid pinned browser version")
        version, release = tag.split("-", 1)
        self._version_obj = Version(version=version, release=release)
        if not self._version_obj.is_supported():
            raise ValueError("Browser version is unsupported by the pinned Python package")
        self._url = (
            f"https://github.com/daijro/camoufox/releases/download/v{tag}/"
            f"camoufox-{tag}-lin.{self.arch}.zip"
        )

    @staticmethod
    def download_file(file, url):
        # BuildKit preserves this download even if extraction or later steps fail.
        cache = Path("/browser-downloads") / url.rsplit("/", 1)[1]
        cache.parent.mkdir(parents=True, exist_ok=True)
        if not cache.is_file():
            CamoufoxFetcher.download_file(file, url)
            partial = cache.with_suffix(".part")
            with partial.open("wb") as target:
                shutil.copyfileobj(file, target)
            partial.replace(cache)
        else:
            with cache.open("rb") as source:
                shutil.copyfileobj(source, file)
        file.seek(0)
        return file


def install_geoip() -> None:
    # Direct release download avoids the separate API quota used by download_mmdb.
    MMDB_FILE.parent.mkdir(parents=True, exist_ok=True)
    partial = MMDB_FILE.with_suffix(".part")
    started, downloaded, reported = time.monotonic(), 0, 0
    try:
        with requests.get(
            "https://github.com/P3TERX/GeoLite.mmdb/releases/latest/download/GeoLite2-City.mmdb",
            timeout=(20, 30),
            stream=True,
        ) as response:
            response.raise_for_status()
            with partial.open("wb") as target:
                for chunk in response.iter_content(65536):
                    if time.monotonic() - started > 180:
                        raise TimeoutError("GeoIP download exceeded 180 seconds")
                    target.write(chunk)
                    downloaded += len(chunk)
                    if downloaded - reported >= 8 * 1024 * 1024:
                        print(f"GeoIP downloaded {downloaded // (1024 * 1024)} MiB", flush=True)
                        reported = downloaded
        partial.replace(MMDB_FILE)
    finally:
        partial.unlink(missing_ok=True)


if __name__ == "__main__":
    if sys.argv[1:] == ["geoip"]:
        install_geoip()
    else:
        PinnedFetcher().install()
