# syntax=docker/dockerfile:1@sha256:4edf897a3ffa55b89f906fc8cc78afdb3f1834cc9c7083565e611a8a7d5fe99e
FROM maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
WORKDIR /src/services/core-service
COPY services/core-service/pom.xml pom.xml
COPY services/core-service/maven-settings.xml maven-settings.xml
RUN --mount=type=cache,id=pdd-maven,target=/root/.m2,sharing=locked \
    mvn --settings maven-settings.xml -B -DskipTests dependency:go-offline
RUN --mount=type=cache,id=pdd-maven,target=/root/.m2,sharing=locked \
    --mount=type=cache,id=pdd-playwright,target=/root/.cache/ms-playwright,sharing=locked \
    mvn --settings maven-settings.xml -B dependency:build-classpath -Dmdep.outputFile=/tmp/playwright-classpath \
    && PLAYWRIGHT_BROWSERS_PATH=/root/.cache/ms-playwright \
      java -cp "$(cat /tmp/playwright-classpath)" com.microsoft.playwright.CLI install chromium \
    && mkdir -p /ms-playwright \
    && cp -a /root/.cache/ms-playwright/. /ms-playwright/
COPY services/core-service/src src
RUN --mount=type=cache,id=pdd-maven,target=/root/.m2,sharing=locked \
    mvn --settings maven-settings.xml -B -DskipTests package

FROM eclipse-temurin:21-jre-noble@sha256:0eda5830ed7b669abc246a8dc520ea8a23cab06f9a8d4b6964109e2f2394e46f
WORKDIR /app
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
RUN apt-get update && apt-get install -y --no-install-recommends \
      curl xauth novnc tigervnc-standalone-server tigervnc-tools \
      ca-certificates fonts-liberation libasound2t64 libatk1.0-0t64 libatk-bridge2.0-0t64 \
      libatspi2.0-0t64 libcairo2 libcups2t64 libdbus-1-3 libdrm2 libgbm1 libglib2.0-0t64 \
      libnspr4 libnss3 libpango-1.0-0 libgtk-3-0t64 fonts-noto-cjk fonts-noto-color-emoji \
      libx11-6 libx11-xcb1 libxcb1 libxcomposite1 \
      libxdamage1 libxext6 libxfixes3 libxkbcommon0 libxrandr2 libxrender1 libxshmfence1 \
      libxss1 libxtst6 libfontconfig1 libfreetype6 libu2f-udev libvulkan1 xdg-utils \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10002 --create-home copilot \
    && mkdir -p /data /ms-playwright \
    && chown -R 10002:10002 /data
COPY --from=build --chown=10002:10002 /ms-playwright /ms-playwright
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
COPY --from=build /src/services/core-service/target/core-service-0.1.0.jar app.jar
ENV PDD_LOGIN_VIEWER_BIND_HOST=0.0.0.0
USER 10002:10002
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
