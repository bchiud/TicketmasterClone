# syntax=docker/dockerfile:1

# --- build stage: compile and package the Spring Boot fat jar ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copy the pom first so dependency downloads are cached until it changes.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

COPY src/ src/
# Tests need a live Postgres and Redis, so they run outside the image build (./mvnw test).
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

# --- runtime stage: JRE only, no build tools or sources ---
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN useradd --system --uid 10001 app
COPY --from=build /app/target/ticketmaster-0.0.1-SNAPSHOT.jar app.jar
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
