# PUBLIC HEALTH API — image de production
# Construit avec Maven, s'exécute en JDK 21 minimal, utilisateur non root.
# Utilisable tel quel sur Render, en auto-hébergement Docker (D4) ou en K8s futur.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
RUN mvn -B -ntp package -DskipTests

FROM eclipse-temurin:21-jre AS runtime
RUN groupadd -r ph && useradd -r -g ph ph
WORKDIR /app
COPY --from=build /app/target/public-health-api-0.1.0-SNAPSHOT.jar app.jar
USER ph
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
