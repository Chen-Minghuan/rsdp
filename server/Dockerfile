# RSDP Spring Boot 后端（多阶段：依赖 → 测试 → 打包 → 运行）
# Jenkins「Java tests」阶段使用 --target test 构建并提取 surefire 报告；
# 本地开发 mvn package 后直接 docker build 同样适用（无需宿主预编译）。

FROM maven:3.9.11-eclipse-temurin-21-alpine AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src

FROM build AS test
RUN mvn -B test

FROM test AS package
RUN mvn -B package -DskipTests

FROM eclipse-temurin:21-jre-alpine

ENV TZ=Asia/Shanghai

WORKDIR /app

COPY --from=package /build/target/rsdp-server-*.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
