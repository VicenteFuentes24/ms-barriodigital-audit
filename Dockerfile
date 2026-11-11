# Build the jar first with .\mvnw.cmd -B -ntp verify
FROM eclipse-temurin:21.0.5_11-jre-jammy
WORKDIR /app
COPY --chown=10001:10001 target/ms-audit-0.0.1-SNAPSHOT.jar /app/ms-audit.jar
USER 10001:10001
EXPOSE 8084
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "-jar", "/app/ms-audit.jar"]
