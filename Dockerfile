# syntax=docker/dockerfile:1

# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app --create-home app
COPY --from=build /build/target/code-review-ai-agent-*.jar /app/app.jar
RUN chown -R app:app /app
USER app

EXPOSE 7003
ENV JAVA_OPTS="-Xms512m -Xmx2g"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
