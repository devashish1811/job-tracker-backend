FROM maven:3.9-eclipse-temurin-8 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:8-jre
WORKDIR /app
COPY --from=build /app/target/job-tracker-backend-1.0.0.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-Xms128m", "-Xmx300m", "-XX:MaxMetaspaceSize=100m", "-XX:ReservedCodeCacheSize=32m", "-jar", "app.jar", "--spring.profiles.active=prod"]
