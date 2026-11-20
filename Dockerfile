# Stage 1: build the jar inside a JDK 21 Maven image (host needs only Docker).
# Uses the image's mvn instead of ./mvnw so Windows CRLF line endings in mvnw cannot break the build.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src/ src/
RUN mvn -q -B clean package -DskipTests

# Stage 2: minimal JRE runtime, non-root user.
FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/target/servicing-agent-poc-*.jar app.jar
USER app
EXPOSE 8080
# ZGC (generational) for low pause times; conservative heap for a 16GB laptop (NFR-2).
ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:+ZGenerational", "-Xms256m", "-Xmx512m", "-jar", "/app/app.jar"]
