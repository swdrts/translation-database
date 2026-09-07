FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY mvn-docker-settings.xml /root/.m2/settings.xml
COPY pom.xml .
RUN mvn -q -s /root/.m2/settings.xml dependency:go-offline
COPY src ./src
RUN mvn -q -s /root/.m2/settings.xml package -DskipTests

FROM eclipse-temurin:21-jre
RUN useradd -r -u 1001 transdb
USER transdb
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
ENV JAVA_OPTS=""
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
