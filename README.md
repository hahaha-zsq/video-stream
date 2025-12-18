# 📹 Video Stream Middleware

<div align="center">

![Java](https://img.shields.io/badge/Java-21-orange?style=flat-square&logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.0-brightgreen?style=flat-square&logo=spring)
![Netty](https://img.shields.io/badge/Netty-4.2.7-blue?style=flat-square)
![FFmpeg](https://img.shields.io/badge/FFmpeg-6.0-red?style=flat-square&logo=ffmpeg)
![License](https://img.shields.io/badge/License-MIT-yellow?style=flat-square)

**一个高性能的视频流转码中间件，将 RTSP 流实时转换为 HTTP-FLV 格式，支持多客户端流复用**

[功能特性](#-功能特性) • [快速开始](#-快速开始) • [架构设计](#-架构设计) • [API文档](#-api-文档) • [配置说明](#-配置说明)

</div>

---

## 📋 目录

- [项目简介](#-项目简介)
- [功能特性](#-功能特性)
- [技术栈](#-技术栈)
- [系统架构](#-系统架构)
- [工作流程](#-工作流程)
- [快速开始](#-快速开始)
- [API 文档](#-api-文档)
- [配置说明](#-配置说明)
- [核心组件](#-核心组件)
- [性能优化](#-性能优化)
- [常见问题](#-常见问题)
- [贡献指南](#-贡献指南)
- [许可证](#-许可证)

---

## 🎯 项目简介

**Video Stream Middleware** 是一个基于 Spring Boot 和 Netty 的高性能视频流转码中间件。它能够将来自摄像头或其他设备的 RTSP 流实时转换为 HTTP-FLV 格式，方便在浏览器中直接播放，无需额外插件。

### 💡 为什么选择这个项目？

- **🚀 零延迟转码**：采用 ultrafast 编码预设和 zerolatency 调优，实现毫秒级延迟
- **🔄 智能流复用**：多个客户端请求同一设备时自动复用转码任务，节省服务器资源
- **⚡ 高性能架构**：基于 Netty 的异步非阻塞 I/O，支持海量并发连接
- **🔌 即插即用**：RESTful API 设计，简单易用，快速集成到现有系统
- **🛡️ 资源自管理**：无人观看时自动释放转码任务，避免资源浪费
- **🎨 浏览器友好**：输出标准 HTTP-FLV 格式，支持 flv.js 等主流播放器

---

## ✨ 功能特性

### 核心功能

| 功能 | 描述 | 状态 |
|------|------|------|
| 🎥 **RTSP 转 HTTP-FLV** | 实时转码 RTSP 流为 HTTP-FLV 格式 | ✅ 已实现 |
| 🔁 **流复用机制** | 多客户端共享同一转码任务 | ✅ 已实现 |
| 🌐 **多客户端广播** | 一对多推流，支持无限客户端并发 | ✅ 已实现 |
| 🔄 **自动资源回收** | 无观看者时自动停止转码任务 | ✅ 已实现 |
| ⚙️ **灵活配置** | 支持线程池、编码参数等多项配置 | ✅ 已实现 |
| 📊 **健康监控** | 集成 Spring Actuator 健康检查 | ✅ 已实现 |

### 技术亮点

- ✅ **H.264 视频编码** + **AAC 音频编码**
- ✅ **TCP 传输协议**，提升 RTSP 连接稳定性
- ✅ **零拷贝优化**，使用直接内存减少内存拷贝
- ✅ **背压控制**，通过水位线机制防止内存溢出
- ✅ **优雅关闭**，Spring 生命周期管理，确保资源正确释放

---

## 🛠 技术栈

### 核心框架

| 技术 | 版本 | 用途 |
|------|------|------|
| ☕ **Java** | 21 | 编程语言 |
| 🍃 **Spring Boot** | 3.2.0 | 应用框架 |
| 🌐 **Netty** | 4.2.7 | 异步网络通信框架 |
| 🎬 **JavaCV** | 1.5.9 | FFmpeg Java 封装库 |
| 🎞️ **FFmpeg** | 6.0 | 音视频编解码库 |

### 辅助工具

| 技术 | 用途 |
|------|------|
| 🔧 **Lombok** | 简化 Java 代码 |
| 🧰 **Hutool** | Java 工具类库 |
| 📝 **Logback** | 日志框架 |
| 📈 **Spring Actuator** | 应用监控 |

---

## 🏗 系统架构

### 整体架构图

```mermaid
graph TB
    subgraph 客户端层
        A1[Web浏览器]
        A2[移动App]
        A3[其他客户端]
    end
    
    subgraph 中间件层
        B1[Netty Server]
        B2[LiveHandler]
        B3[MediaStreamService]
        B4[TransferToFlv]
        B5[MediaChannel]
    end
    
    subgraph 数据源层
        C1[摄像头 1]
        C2[摄像头 2]
        C3[摄像头 N]
    end
    
    A1 -->|HTTP-FLV请求| B1
    A2 -->|HTTP-FLV请求| B1
    A3 -->|HTTP-FLV请求| B1
    
    B1 --> B2
    B2 --> B3
    B3 --> B4
    B4 --> B5
    B5 -->|FLV流| A1
    B5 -->|FLV流| A2
    B5 -->|FLV流| A3
    
    B4 -->|RTSP拉流| C1
    B4 -->|RTSP拉流| C2
    B4 -->|RTSP拉流| C3
```

### 核心组件关系图

```mermaid
graph LR
    A[LiveHandler] -->|创建/复用| B[TransferToFlv]
    A -->|注册任务| C[MediaStreamService]
    B -->|管理客户端| D[MediaChannel]
    D -->|发布事件| E[CloseStreamEvent]
    E -->|监听| C
    C -->|停止任务| B
    D -->|广播FLV数据| F[HTTP客户端]
```

---

## 🔄 工作流程

### 时序图：客户端请求流

```mermaid
sequenceDiagram
    participant Client as 客户端
    participant Netty as Netty Server
    participant Handler as LiveHandler
    participant Service as MediaStreamService
    participant Transfer as TransferToFlv
    participant Channel as MediaChannel
    participant RTSP as RTSP摄像头

    Client->>Netty: GET /live?deviceId=xxx&rtspUrl=xxx
    Netty->>Handler: channelRead0(request)
    Handler->>Service: getStreamTask(deviceId)
    
    alt 任务已存在（流复用）
        Service-->>Handler: 返回现有任务
        Handler->>Channel: addChannel(ctx, true)
        Channel->>Client: 发送FLV头部
        Note over Channel,Client: 复用现有转码流
    else 任务不存在（新建任务）
        Service-->>Handler: 返回null
        Handler->>Transfer: new TransferToFlv()
        Handler->>Channel: new MediaChannel()
        Handler->>Service: registerTask(device, task)
        Handler->>Transfer: start() 启动转码线程
        
        Transfer->>RTSP: 连接RTSP流
        RTSP-->>Transfer: 返回视频帧
        
        loop 实时转码循环
            Transfer->>Transfer: 抓取视频帧
            Transfer->>Transfer: 编码为FLV格式
            Transfer->>Channel: sendData(flvData)
            Channel->>Client: 推送FLV数据
        end
    end
    
    Client->>Netty: 断开连接
    Channel->>Channel: checkChannel()
    
    alt 无客户端观看
        Channel->>Service: publishEvent(CloseStreamEvent)
        Service->>Transfer: stop()
        Transfer->>RTSP: 断开连接
        Transfer->>Channel: closeChannel()
    end
```

### 流程说明

#### 1️⃣ **客户端请求阶段**
- 客户端发送 HTTP 请求：`/live?deviceId=camera001&rtspUrl=rtsp://example.com/stream`
- `LiveHandler` 解析参数并查询 `MediaStreamService`

#### 2️⃣ **任务创建/复用阶段**
- **如果任务已存在**：直接将客户端添加到 `MediaChannel`，复用现有转码流
- **如果任务不存在**：创建新的 `TransferToFlv` 任务，启动转码线程

#### 3️⃣ **实时转码阶段**
- `TransferToFlv` 从 RTSP 流抓取视频帧
- 使用 H.264 编码器将帧转换为 FLV 格式
- 通过 `MediaChannel` 广播给所有订阅客户端

#### 4️⃣ **资源回收阶段**
- 客户端断开时，`MediaChannel` 定时检查（每 10 秒）
- 如果所有客户端已断开，发布 `CloseStreamEvent`
- `MediaStreamService` 监听事件，调用 `TransferToFlv.stop()` 停止转码
- 释放所有资源（RTSP 连接、编码器、内存缓冲区）

---

## 🚀 快速开始

### 环境要求

- ☕ **JDK 21** 或更高版本
- 📦 **Maven 3.6+**
- 🎬 **FFmpeg** 运行时库（JavaCV 会自动下载）

### 安装步骤

#### 1. 克隆项目

```bash
git clone https://github.com/yourusername/video-stream-middleware.git
cd video-stream-middleware
```

#### 2. 编译项目

```bash
mvn clean package -DskipTests
```

#### 3. 运行应用

```bash
java -jar target/video-stream-middleware-1.0-SNAPSHOT.jar
```

或使用 Maven 插件运行：

```bash
mvn spring-boot:run
```

#### 4. 验证服务

访问健康检查接口：

```bash
curl http://localhost:8080/actuator/health
```

预期响应：

```json
{
  "status": "UP"
}
```

---

## 📡 API 文档

### 获取实时视频流

#### 请求

```http
GET /live?deviceId={deviceId}&rtspUrl={rtspUrl}
```

#### 参数说明

| 参数 | 类型 | 必填 | 描述 | 示例 |
|------|------|------|------|------|
| `deviceId` | String | ✅ | 设备唯一标识 | `camera001` |
| `rtspUrl` | String | ✅ | RTSP 流地址 | `rtsp://admin:password@192.168.1.100:554/stream1` |

#### 响应

- **Content-Type**: `video/x-flv`
- **Transfer-Encoding**: `chunked`
- **Body**: FLV 视频流（持续推送）

#### 示例

**请求：**

```bash
curl "http://localhost:8080/live?deviceId=camera001&rtspUrl=rtsp://admin:123456@192.168.1.100:554/stream1"
```

**浏览器中使用 flv.js 播放：**

```html
<!DOCTYPE html>
<html>
<head>
    <title>视频直播</title>
    <script src="https://cdn.jsdelivr.net/npm/flv.js@latest"></script>
</head>
<body>
    <video id="videoElement" controls width="800" height="600"></video>
    <script>
        if (flvjs.isSupported()) {
            var videoElement = document.getElementById('videoElement');
            var flvPlayer = flvjs.createPlayer({
                type: 'flv',
                isLive: true,
                url: 'http://localhost:8080/live?deviceId=camera001&rtspUrl=rtsp://admin:123456@192.168.1.100:554/stream1'
            });
            flvPlayer.attachMediaElement(videoElement);
            flvPlayer.load();
            flvPlayer.play();
        }
    </script>
</body>
</html>
```

---

## ⚙️ 配置说明

### application.yml 配置

```yaml
server:
  compression:
    enabled: true
    # 配置哪些 MIME 类型的内容会被压缩，默认包括了大部分文本类型
    mime-types: text/html,text/xml,text/plain,text/css,application/javascript,application/json
    # 只有当响应体的大小大于或等于这个值（以字节为单位）时才会启用压缩，默认是 2048 字节
    min-response-size: 1024
  port: 8080
spring:
  application:
    name: video-stream-middleware
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}
```
### application-dev.yml 配置

```yaml
log_path: ${LOG_PATH:/logs}
log_name: video-stream-middleware
spring:
  servlet:
    multipart:
      max-file-size: -1
      max-request-size: -1
netty-video:
  # 服务端配置
  server:
    # 服务端口
    port: ${NETTY_PORT:8888}
    # 线程池配置
    threadPool:
      core-pool-size: 5
      max-pool-size: 10
      queue-capacity: 50
      keep-alive-seconds: 10
      name-prefix: "nettyAsyncExecutor -"
      await-termination-seconds: 60
      wait-for-tasks-to-complete-on-shutdown: true
```
### 构建/运行镜像
```shell
docker build -t video-stream-middleware:0.0.1 .
```

```shell
    docker run -d \
        --name video-stream-middleware \
        -p 8080:8080 \
        -p 8888:8888 \
        -v $(pwd)/logs:/app/logs \
        --restart unless-stopped \
        video-stream-middleware:0.0.1
```

### Netty 配置（NettyProperties.java）

| 配置项 | 默认值 | 描述 |
|--------|--------|------|
| `server.port` | 8888 | Netty 服务器端口（当前未使用） |
| `server.bossThreads` | 1 | Boss 线程组大小 |
| `server.workerThreads` | CPU 核心数 × 2 | Worker 线程组大小 |
| `server.maxConnections` | 1000 | 最大连接数 |
| `server.maxFrameSize` | 65536 | 最大帧大小（64KB） |

### FFmpeg 编码参数

| 参数 | 值 | 说明 |
|------|-----|------|
| `format` | flv | 输出格式 |
| `videoCodec` | H.264 | 视频编码器 |
| `audioCodec` | AAC | 音频编码器 |
| `preset` | ultrafast | 编码速度（最快） |
| `tune` | zerolatency | 零延迟优化 |
| `crf` | 25 | 恒定质量因子（0-51） |
| `frameRate` | 25 | 输出帧率 |
| `gopSize` | 25 | GOP 大小（1 秒） |

---

## 🧩 核心组件

### 1. NettyServer

**职责**：Netty 服务器启动和管理

- 启动 Boss 和 Worker 线程组
- 配置服务器参数（SO_BACKLOG、TCP_NODELAY 等）
- 优雅关闭资源

### 2. LiveHandler

**职责**：处理 HTTP-FLV 请求

- 解析请求参数（deviceId、rtspUrl）
- 创建或复用转码任务
- 发送 FLV 响应头

### 3. MediaStreamService

**职责**：管理转码任务生命周期

- 注册和查询转码任务
- 监听流关闭事件
- 停止转码任务

### 4. TransferToFlv

**职责**：RTSP 转 FLV 转码

- 连接 RTSP 流并抓取视频帧
- 使用 H.264/AAC 编码
- 实时推送 FLV 数据

### 5. MediaChannel

**职责**：管理客户端连接

- 维护客户端连接集合
- 广播 FLV 数据给所有客户端
- 定时检查连接状态
- 发布流关闭事件

---


## 🙏 致谢

- [Spring Boot](https://spring.io/projects/spring-boot) - 强大的应用框架
- [Netty](https://netty.io/) - 高性能网络框架
- [JavaCV](https://github.com/bytedeco/javacv) - FFmpeg Java 封装
- [FFmpeg](https://ffmpeg.org/) - 音视频处理利器

---

## 📞 联系方式

- 📧 Email: your-email@example.com
- 🐛 Issues: [GitHub Issues](https://github.com/yourusername/video-stream-middleware/issues)
- 💬 Discussions: [GitHub Discussions](https://github.com/yourusername/video-stream-middleware/discussions)

---

<div align="center">

**如果这个项目对你有帮助，请给个 ⭐️ Star 支持一下！**

Made with ❤️ by Video Stream Middleware Team

</div>
