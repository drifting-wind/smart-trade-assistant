FROM eclipse-temurin:17-jre

WORKDIR /app
COPY target/smart-trade-assistant-1.0.0.jar /app/app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
