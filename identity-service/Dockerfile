# Build context = repository root
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY common/pom.xml common/pom.xml
COPY identity-service/pom.xml identity-service/pom.xml
COPY leave-service/pom.xml leave-service/pom.xml
COPY api-gateway/pom.xml api-gateway/pom.xml
COPY common/src common/src
COPY identity-service/src identity-service/src
RUN mvn -B -q -pl identity-service -am package -DskipTests

FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build /build/identity-service/target/identity-service.jar /app/identity-service.jar
USER app
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/identity-service.jar"]
