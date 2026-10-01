FROM maven:3.9.11-eclipse-temurin-25-alpine AS build

WORKDIR /workspace
COPY pom.xml ./
COPY services/yurlib-server/pom.xml services/yurlib-server/pom.xml
COPY services/yurlib-document-worker/pom.xml services/yurlib-document-worker/pom.xml
RUN mvn -B -pl services/yurlib-document-worker -am dependency:go-offline

COPY services/yurlib-document-worker/src services/yurlib-document-worker/src
RUN mvn -B -pl services/yurlib-document-worker -am package -DskipTests

FROM eclipse-temurin:25-jre-alpine

RUN addgroup -S yurlib && adduser -S -G yurlib -u 10001 yurlib

WORKDIR /app
COPY --from=build --chown=yurlib:yurlib /workspace/services/yurlib-document-worker/target/yurlib-document-worker-*.jar /app/worker.jar
COPY --from=build --chown=yurlib:yurlib /workspace/services/yurlib-document-worker/target/lib /app/lib
COPY --chown=yurlib:yurlib services/yurlib-document-worker/THIRD-PARTY-NOTICES.md /app/THIRD-PARTY-NOTICES.md

USER 10001:10001
ENTRYPOINT ["java", "-Xmx128m", "-XX:MaxDirectMemorySize=32m", "-cp", "/app/worker.jar:/app/lib/*", "org.yurlib.worker.YurlibDocumentWorker"]
