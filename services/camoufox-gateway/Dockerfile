FROM python:3.11-slim-bookworm AS dependencies
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 XDG_CACHE_HOME=/opt/browser-cache
WORKDIR /app
COPY requirements.txt .
RUN --mount=type=cache,id=pdd-camoufox-pip,target=/root/.cache/pip,sharing=locked \
    pip install -r requirements.txt
RUN python -m playwright install-deps firefox \
    && rm -rf /var/lib/apt/lists/*
# Install in a separate stage: changing application code does not rebuild the browser.
FROM dependencies AS browser-build
ARG CAMOUFOX_BROWSER_VERSION=135.0.1-beta.24
COPY install_browser.py ./
RUN --mount=type=cache,id=pdd-camoufox-downloads,target=/browser-downloads \
    CAMOUFOX_BROWSER_VERSION=$CAMOUFOX_BROWSER_VERSION python install_browser.py
RUN python install_browser.py geoip

FROM dependencies
RUN useradd --create-home --uid 10001 gateway
# Set ownership while copying; recursive chown would duplicate the browser layer.
COPY --from=browser-build --chown=10001:10001 /opt/browser-cache /opt/browser-cache
# The pinned package stores GeoIP beside its Python module, outside XDG_CACHE_HOME.
COPY --from=browser-build /usr/local/lib/python3.11/site-packages/camoufox/GeoLite2-City.mmdb /usr/local/lib/python3.11/site-packages/camoufox/GeoLite2-City.mmdb
COPY server.py lifecycle.py worker.py browser-server.cjs ./
USER gateway
EXPOSE 8000
# One worker owns the in-memory process registry; scale with separate containers.
CMD ["uvicorn", "server:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1", "--no-access-log"]
