# Build context: repository root (docker build -f leave-service/Dockerfile .)
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY common/ common/
COPY leave-service/ leave-service/
# the parent pom lists every module, so their manifests must be present for the reactor to load
COPY identity-service/pom.xml identity-service/pom.xml
COPY api-gateway/pom.xml api-gateway/pom.xml
RUN mvn -B -q -pl leave-service -am package -DskipTests

FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --no-create-home app \
    && mkdir -p /data/uploads \
    && chown -R app:app /data/uploads
WORKDIR /app
COPY --from=build /build/leave-service/target/leave-service.jar /app/leave-service.jar
ENV UPLOAD_DIR=/data/uploads
VOLUME /data/uploads
USER app
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/leave-service.jar"]
