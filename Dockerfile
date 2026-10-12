# syntax=docker/dockerfile:1
#
# Kendar DLNA music server.
#
#   docker build -t kendar-dlna .
#   docker build --build-arg SKIP_TESTS=false --build-arg GIT_COMMIT=$(git rev-parse --short HEAD) \
#                --build-arg BUILD_TIME=$(date -u +%FT%TZ) -t kendar-dlna .
#
# Architectures without an Alpine Temurin image (check the tags of https://hub.docker.com/_/eclipse-temurin):
# build with  --build-arg RUNTIME_IMAGE=eclipse-temurin:25-jre  (Ubuntu based; the entrypoint then uses gosu, see
# the commented lines in the runtime stage). The application is pure Java, so nothing else changes.

ARG JDK_IMAGE=eclipse-temurin:25-jdk
ARG RUNTIME_IMAGE=eclipse-temurin:25-jre-alpine

# ---------------------------------------------------------------- build stage
FROM ${JDK_IMAGE} AS build
WORKDIR /src

# Dependency layer: only the build files, so that source changes do not re-download Gradle and the libraries.
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle ./gradle
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew \
    && ./gradlew --no-daemon --console=plain dependencies > /dev/null

# Build identification (shown in the admin page); read by build.gradle from GIT_COMMIT / BUILD_TIME.
ARG GIT_COMMIT=unknown
ARG BUILD_TIME=unknown
ARG SKIP_TESTS=true
ENV GIT_COMMIT=${GIT_COMMIT} BUILD_TIME=${BUILD_TIME}

COPY src ./src
RUN if [ "$SKIP_TESTS" = "true" ]; then \
        ./gradlew --no-daemon --console=plain shadowJar; \
    else \
        ./gradlew --no-daemon --console=plain build; \
    fi \
    && cp build/libs/kendar-dlna.jar /kendar-dlna.jar

# ------------------------------------------------------------- runtime stage
FROM ${RUNTIME_IMAGE}

# Alpine: su-exec. On the Ubuntu-based fallback image replace this RUN with:
#   RUN apt-get update && apt-get install -y --no-install-recommends gosu && rm -rf /var/lib/apt/lists/*
RUN if command -v apk >/dev/null 2>&1; then apk add --no-cache su-exec; fi

# ffmpeg decodes WMA and APE (no Java decoder exists for them). The package manager installs the build matching the
# image architecture (amd64, arm64, ...), so there is nothing to pick by hand. Skip it with --build-arg INSTALL_FFMPEG=false
# (WMA / APE files are then listed as unsupported); a binary elsewhere can be named with KENDAR_FFMPEG_PATH.
ARG INSTALL_FFMPEG=true
RUN if [ "$INSTALL_FFMPEG" = "true" ]; then \
        if command -v apk >/dev/null 2>&1; then apk add --no-cache ffmpeg; \
        else apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*; fi; \
    fi

ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    TZ=UTC \
    PUID=1000 \
    PGID=1000 \
    KENDAR_CONFIG_DIR=/config \
    KENDAR_DATA_DIR=/data \
    KENDAR_DEFAULT_ROOT=/music \
    KENDAR_PORT=8200

WORKDIR /app
COPY --from=build /kendar-dlna.jar /app/kendar-dlna.jar
COPY docker/entrypoint.sh /app/entrypoint.sh
RUN sed -i 's/\r$//' /app/entrypoint.sh && chmod 755 /app/entrypoint.sh \
    && mkdir -p /music /playlists /config /data

VOLUME ["/music", "/playlists", "/config", "/data"]
EXPOSE 8200

# busybox wget (Alpine); the Ubuntu-based Temurin image has curl instead, hence the fallback.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -q -O /dev/null "http://127.0.0.1:${KENDAR_PORT:-8200}/health" \
     || curl -fsS -o /dev/null "http://127.0.0.1:${KENDAR_PORT:-8200}/health" || exit 1

ENTRYPOINT ["/app/entrypoint.sh"]
