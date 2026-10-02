FROM maven:3.9.11-eclipse-temurin-25-alpine AS build

WORKDIR /workspace
COPY pom.xml ./
COPY services/yurlib-server/pom.xml services/yurlib-server/pom.xml
COPY services/yurlib-document-worker/pom.xml services/yurlib-document-worker/pom.xml
RUN mvn -B -pl services/yurlib-document-worker -am dependency:go-offline

COPY services/yurlib-document-worker/src services/yurlib-document-worker/src
RUN mvn -B -pl services/yurlib-document-worker -am package -DskipTests

FROM alpine:3.22 AS calibre

ARG TARGETARCH
ARG CALIBRE_VERSION=9.15.0
RUN apk add --no-cache curl xz \
    && case "${TARGETARCH}" in \
        amd64) artifact_arch=x86_64; checksum=77fb209188906a1be5ca1e8147339bd06242414ffbfe7dbc54e65667c8d6057fdc6e709efff0c415c42fb95ca5f53359ae041d5089cd9927a0d1d74d9fc8f4a5 ;; \
        arm64) artifact_arch=arm64; checksum=725bae1ccbc5501df9cb92c1df4df9d335cd470dd8681c099bf7c1c7a0c05845f139a066c22dc38374c58fde0c4c2df21e9139e1a25d4917e0fd2183f2fcbafb ;; \
        *) echo "Unsupported worker architecture: ${TARGETARCH}" >&2; exit 1 ;; \
    esac \
    && curl --fail --location --retry 3 --output /tmp/calibre.txz \
        "https://download.calibre-ebook.com/${CALIBRE_VERSION}/calibre-${CALIBRE_VERSION}-${artifact_arch}.txz" \
    && echo "${checksum}  /tmp/calibre.txz" | sha512sum -c - \
    && mkdir -p /opt/calibre \
    && tar -xJf /tmp/calibre.txz -C /opt/calibre

FROM eclipse-temurin:25-jre-noble

RUN apt-get update \
    && apt-get install --yes --no-install-recommends \
        djvulibre-bin=3.5.28-2ubuntu0.24.04.2 \
        libegl1=1.7.0-1build1 \
        libglx0=1.7.0-1build1 \
        libopengl0=1.7.0-1build1 \
        libxkbcommon0=1.6.0-1build1 \
    && apt-get clean \
    && find /var/lib/apt/lists -mindepth 1 -delete \
    && groupadd --system --gid 10001 yurlib \
    && useradd --system --uid 10001 --gid 10001 --home-dir /app --shell /usr/sbin/nologin yurlib

WORKDIR /app
COPY --from=calibre --chown=yurlib:yurlib /opt/calibre /opt/calibre
COPY --from=build --chown=yurlib:yurlib /workspace/services/yurlib-document-worker/target/yurlib-document-worker-*.jar /app/worker.jar
COPY --from=build --chown=yurlib:yurlib /workspace/services/yurlib-document-worker/target/lib /app/lib
COPY --chown=yurlib:yurlib services/yurlib-document-worker/THIRD-PARTY-NOTICES.md /app/THIRD-PARTY-NOTICES.md

USER 10001:10001
ENV CALIBRE_CONFIG_DIRECTORY=/work/calibre-config \
    CALIBRE_TEMP_DIR=/work/calibre-temp \
    QT_QPA_PLATFORM=offscreen
ENTRYPOINT ["java", "-Xmx128m", "-XX:MaxDirectMemorySize=32m", "-cp", "/app/worker.jar:/app/lib/*", "org.yurlib.worker.YurlibDocumentWorker"]
