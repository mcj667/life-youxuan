FROM eclipse-temurin:8-jre
WORKDIR /app
COPY target/life-youxuan-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java","-jar","app.jar"]
