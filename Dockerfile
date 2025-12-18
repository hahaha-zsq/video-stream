# ==============================
# Stage 1: 构建阶段
# ==============================
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /app

# 复制配置文件
COPY settings.xml ./
COPY pom.xml ./
# 复制源码
COPY src ./src

# 执行构建
# 关键点：使用 -P linux 强制激活 Linux Profile，只打包 Linux 依赖
# -s settings.xml 使用自定义镜像源加速下载
RUN mvn clean package -DskipTests -P linux -s settings.xml -B -U && rm -rf .m2

# ==============================
# Stage 2: 运行阶段
# ==============================
# ⚠️ 关键点：使用基于 Ubuntu 的镜像 (jammy)，原生支持 glibc
# 这解决了 Alpine 系统下缺少 glibc 导致 JavaCV 无法加载 .so 库的问题
FROM eclipse-temurin:21-jre-jammy

# 设置时区
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 创建应用和日志目录
WORKDIR /app
RUN mkdir -p /app/logs /dumps

# 从构建阶段复制 JAR 包
COPY --from=builder /app/target/app.jar /app/app.jar

# JVM 和应用配置
# 开启 G1GC，配置 OOM 自动 Dump
ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/dumps/oom_dump.hprof"
ENV SPRING_PROFILES_ACTIVE="--spring.profiles.active=prod"

# 暴露端口
EXPOSE 8080 8888

# 启动命令
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /app/app.jar ${SPRING_PROFILES_ACTIVE}"]