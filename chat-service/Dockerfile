# 前置: 先在仓库根目录执行 mvn -DskipTests package
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/chat-service-1.0-SNAPSHOT.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
