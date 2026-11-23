# Build
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q package -DskipTests

# Run
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/arbplay-odds-0.0.1-SNAPSHOT.jar app.jar
# Render's free instance has 512 MB RAM; Render passes the port in $PORT.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -Xss512k"
EXPOSE 8080
CMD ["sh", "-c", "java $JAVA_OPTS -jar app.jar --server.port=${PORT:-8080}"]
