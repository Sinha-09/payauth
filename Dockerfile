# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies first: this layer is cached until pom.xml actually changes, so
# ordinary source edits do not re-download the world.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- run ----
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Non-root. A payment service should not be running as uid 0.
RUN addgroup -S payauth && adduser -S payauth -G payauth
COPY --from=build /build/target/payauth-*.jar /app/payauth.jar
RUN chown -R payauth:payauth /app
USER payauth

EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=10s --timeout=3s --start-period=45s --retries=10 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/payauth.jar"]
