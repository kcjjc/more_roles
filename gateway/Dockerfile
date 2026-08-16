# 前置: 先在仓库根目录执行 mvn -DskipTests package
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/gateway-1.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
