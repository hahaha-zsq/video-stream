# ==============================
# Stage 1: 构建阶段
# ==============================
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /app

# 先复制 pom.xml 并下载依赖（利用缓存）
COPY settings.xml ./
# 指令用于将宿主机的文件复制到Docker镜像中。这里将宿主机的 pom.xml 文件复制到镜像的工作目录（/usr/src/bun-monomer）下，如果pom.xml不发生变化，docker会使用它的缓存，若
# 发生了变化，Docker 会重新执行 Maven 构建，但这并不意味着所有依赖都会被重新下载；只有那些发生变化或未被本地缓存的依赖会被重新获取
COPY pom.xml ./
# 将宿主机的 src 目录及其内容复制到镜像的工作目录下的 src 子目录中。因此，在镜像中，源代码将被放置在 /usr/src/bun-monomer/src
COPY src ./src
#-DskipTests=true 表示跳过测试阶段，-s 参数用于指定一个自定义的 settings.xml 文件，-B 或 --batch-mode 标志表示以批处理模式运行 Maven，即不与用户交互。这在自动化构建中很有用，因为它可以避免因用户输入而导致的延迟或错误
RUN mvn clean package -DskipTests=true -s settings.xml -B -U && rm -rf .m2 .mvn .mvn/wrapper

# ==============================
# Stage 2: 运行阶段（使用 Alpine 减小体积）
# ==============================
FROM eclipse-temurin:21-jre-alpine
# 安装 ffmpeg（Alpine 使用 apk）
RUN apk add --no-cache ffmpeg
#同步docker内部的时间一般不需要改  创建堆转储文件目录
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone && mkdir -p /dumps
# JVM 和应用配置
ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/dumps/oom_dump.hprof"
ENV SPRING_PROFILES_ACTIVE="--spring.profiles.active=prod"
ENV LOG_PATH=/app/logs
#设置时区
ENV TZ=Asia/Shanghai
ENV HOME_PATH=/app
# 设置工作目录
WORKDIR ${HOME_PATH}

# 假设你的 Maven 构建生成的 JAR 名为 app.jar（建议在 pom.xml 中设置 <finalName>app</finalName>）
COPY --from=builder /app/target/app.jar ${HOME_PATH}/app.jar
# 暴露端口
EXPOSE 8080 8888

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar ${HOME_PATH}/app.jar ${SPRING_PROFILES_ACTIVE}"]
# 启动命令