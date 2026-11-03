# Stage 1 — Build con Maven (no necesita Java instalado en el servidor)
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2 — Runtime liviano, sin root (servidor compartido con otros proyectos)
FROM eclipse-temurin:21-jre-jammy
RUN groupadd --system --gid 10001 spring && useradd --system --uid 10001 --gid spring --no-create-home spring
WORKDIR /app
COPY --from=build --chown=spring:spring /app/target/PH-0.0.1-SNAPSHOT.jar app.jar
USER spring:spring
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
