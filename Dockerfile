# ==============================
# Stage 1: 构建阶段
# ==============================
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /app

COPY settings.xml ./
COPY pom.xml ./
COPY src ./src

RUN mvn clean package -DskipTests -P linux -s settings.xml -B -U && rm -rf .m2

# ==============================
# Stage 2: 运行阶段
# ==============================
FROM eclipse-temurin:21-jre-jammy

ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 安装系统依赖：ffmpeg runtime、硬件加速库、网络工具
RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    libavcodec-extra \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
RUN mkdir -p /app/logs /app/tmp /dumps

COPY --from=builder /app/target/app.jar /app/app.jar

ENV JAVA_OPTS="-Xms1g -Xmx2g \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=100 \
    -XX:ParallelGCThreads=2 \
    -XX:ConcGCThreads=1 \
    -XX:MaxDirectMemorySize=1g \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath=/dumps/oom_dump.hprof \
    -Djava.io.tmpdir=/app/tmp"
ENV SPRING_PROFILES_ACTIVE="--spring.profiles.active=prod"

EXPOSE 4900 4901

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /app/app.jar ${SPRING_PROFILES_ACTIVE}"]
