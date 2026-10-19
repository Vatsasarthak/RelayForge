FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
# Tests run outside the image build (./mvnw test); they need Docker for Testcontainers.
RUN mvn package -DskipTests -B

FROM eclipse-temurin:21-jre

WORKDIR /app

RUN groupadd --system relayforge && useradd --system --gid relayforge --no-create-home relayforge

COPY --from=build /app/target/*.jar app.jar

USER relayforge

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
