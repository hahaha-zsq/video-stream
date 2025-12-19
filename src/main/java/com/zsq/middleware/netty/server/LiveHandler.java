package com.zsq.middleware.netty.server;

import com.zsq.middleware.model.Device;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * HTTP-FLV 直播请求处理器
 * <p>
 * 核心职责:
 * 1. 处理客户端的 HTTP-FLV 直播请求
 * 2. 解析请求参数(deviceId 和 rtspUrl)
 * 3. 创建或复用转码任务
 * 4. 将客户端连接添加到媒体通道
 * <p>
 * 设计特点:
 * - @Sharable: 可在多个 Channel 中共享,节省资源
 * - 支持流复用:多个客户端请求同一设备时复用同一转码任务
 * - 与 MediaStreamService 协作管理转码任务生命周期
 * <p>
 * 请求示例:
 * GET /live?deviceId=camera001&rtspUrl=rtsp://example.com/stream
 */
@Slf4j
@Service
@ChannelHandler.Sharable
public class LiveHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    /**
     * Spring 事件发布器
     * 用于发布流相关事件(如关闭事件)
     */
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 流管理服务
     * 用于创建、获取、管理转码任务
     */
    private final MediaStreamService streamService;

    /**
     * 构造方法
     * 通过 Spring 注入依赖
     *
     * @param eventPublisher Spring 事件发布器
     * @param streamService  流管理服务
     */
    public LiveHandler(ApplicationEventPublisher eventPublisher,
                       MediaStreamService streamService) {
        this.eventPublisher = eventPublisher;
        this.streamService = streamService;
    }

    /**
     * 处理 HTTP 请求
     * <p>
     * 执行步骤:
     * 1. 解析请求 URI,检查路径是否为 /live
     * 2. 提取 deviceId 和 rtspUrl 参数
     * 3. 发送 FLV 响应头
     * 4. 创建设备对象并启动播放
     *
     * @param ctx Netty 通道上下文
     * @param req HTTP 请求对象
     */
    @Override
    public void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        // 解析请求 URI 和查询参数
        QueryStringDecoder decoder = new QueryStringDecoder(req.uri());

        // 只处理 /live 路径请求，否则返回错误
        if (!"/live".equals(decoder.path())) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST);
            return;
        }

        // 获取 deviceId 和 rtspUrl 参数
        List<String> deviceIdParams = decoder.parameters().get("deviceId");
        List<String> rtspUrlParams = decoder.parameters().get("rtspUrl");

        // 如果参数缺失，返回错误
        if (deviceIdParams == null || deviceIdParams.isEmpty() ||
            rtspUrlParams == null || rtspUrlParams.isEmpty()) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST);
            return;
        }

        String deviceId = deviceIdParams.getFirst(); // 取第一个参数
        String rtspUrl = rtspUrlParams.getFirst();

        // 发送 HTTP 响应头，表明后续返回的是 FLV 流
        sendFlvResHeader(ctx);

        // 构建设备对象
        Device device = new Device(deviceId, rtspUrl);

        // 调用播放方法，为该客户端启动或复用 FLV 转码任务
        playForHttp(device, ctx);
    }

    /**
     * 为 HTTP 客户端启动或复用直播流
     * <p>
     * 核心逻辑:
     * 1. 尝试从 streamService 获取现有转码任务
     * 2. 如果存在,复用现有流(多客户端共享同一转码任务)
     * 3. 如果不存在,创建新的转码任务并启动线程
     * <p>
     * 设计优势:
     * - 节省资源:多个客户端请求同一设备时只创建一个转码任务
     * - 降低延迟:复用现有流时无需重新连接 RTSP
     *
     * @param device 设备信息(包含 deviceId 和 rtspUrl)
     * @param ctx    Netty 通道上下文
     */
    public void playForHttp(Device device, ChannelHandlerContext ctx) {
        try {
            // 1. 尝试从流管理服务获取已存在的转码任务
            TransferToFlv mediaConvert = streamService.getStreamTask(device.getDeviceId());

            // 2. 如果已经存在任务，则复用已有流
            if (mediaConvert != null) {
                log.info("服务端：设备[{}] 已有转码任务，复用现有流", device.getDeviceId());
                mediaConvert.getMediaChannel().addChannel(ctx, true); // 添加客户端到 MediaChannel
                return;
            }

            // 3. 如果不存在，则创建新的转码任务
            log.info("服务端：设备[{}] 首次请求，创建新的转码任务", device.getDeviceId());
            mediaConvert = new TransferToFlv();
            mediaConvert.setCurrentDevice(device);

            // 4. 创建 MediaChannel，用于管理多个客户端连接
            MediaChannel mediaChannel = new MediaChannel(device, eventPublisher, streamService);
            mediaConvert.setMediaChannel(mediaChannel);

            // 5. 将任务注册到流管理服务，便于复用
            streamService.registerTask(device, mediaConvert);

            // 6. 启动转码线程，将 RTSP 转 FLV
            new Thread(mediaConvert, "TransferToFlv-" + device.getDeviceId()).start();

            // 7. 添加当前客户端到 MediaChannel
            mediaConvert.getMediaChannel().addChannel(ctx, false);

            log.info("服务端：设备[{}] 转码任务启动成功", device.getDeviceId());

        } catch (Exception e) {
            // 捕获异常并记录日志，同时抛出 RuntimeException
            log.error("服务端：设备[{}] 启动播放失败", device.getDeviceId(), e);
            throw new RuntimeException("启动播放失败", e);
        }
    }

    /**
     * 发送错误响应
     *
     * @param ctx    Netty 通道上下文
     * @param status HTTP 状态码
     */
    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status,
                Unpooled.copiedBuffer("Error: " + status + "\r\n", CharsetUtil.UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE); // 发送后关闭连接
    }

    /**
     * 发送 FLV 响应头
     * <p>
     * 设置关键响应头:
     * - Content-Type: video/x-flv (告诉客户端这是 FLV 视频流)
     * - Transfer-Encoding: chunked (分块传输,支持实时流)
     * - Cache-Control: no-cache (禁用缓存,确保实时性)
     *
     * @param ctx Netty 通道上下文
     */
    private void sendFlvResHeader(ChannelHandlerContext ctx) {
        HttpResponse rsp = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        rsp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)  // 响应后保持连接
                .set(HttpHeaderNames.CONTENT_TYPE, "video/x-flv")              // FLV 视频流
                .set(HttpHeaderNames.ACCEPT_RANGES, "bytes")                   // 支持分段传输
                .set(HttpHeaderNames.PRAGMA, "no-cache")                       // HTTP/1.0 禁用缓存
                .set(HttpHeaderNames.CACHE_CONTROL, "no-cache")                // HTTP/1.1 禁用缓存
                .set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)  // 分块传输
                .set(HttpHeaderNames.SERVER, "Video-Stream-Middleware");       // 服务器标识
        ctx.writeAndFlush(rsp);
    }

    /**
     * 通道读取完成回调
     * 刷新缓冲区,确保数据发送
     */
    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    /**
     * 异常处理
     * 记录错误并关闭连接
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("连接异常", cause);
        ctx.close(); // 出现异常直接关闭连接
    }
}