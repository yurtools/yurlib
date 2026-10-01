FROM maven:3.9.11-eclipse-temurin-25-alpine AS build

WORKDIR /workspace
COPY pom.xml ./
COPY services/yurlib-server/pom.xml services/yurlib-server/pom.xml
COPY services/yurlib-document-worker/pom.xml services/yurlib-document-worker/pom.xml
RUN mvn -B -pl services/yurlib-server -am dependency:go-offline

COPY contracts contracts
COPY services/yurlib-server/src services/yurlib-server/src
RUN mvn -B -pl services/yurlib-server -am package -DskipTests

FROM eclipse-temurin:25-jre-alpine

RUN addgroup -S yurlib && adduser -S -G yurlib -h /app yurlib
WORKDIR /app
COPY --from=build --chown=yurlib:yurlib /workspace/services/yurlib-server/target/yurlib-server-*.jar app.jar
USER yurlib
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
