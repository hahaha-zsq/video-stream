package com.zsq.middleware.server;

import com.zsq.middleware.model.Device;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.zsq.middleware.server.Common.deviceContext;

/**
 * HTTP-FLV直播流处理器
 * <p>
 * 功能说明：
 * 1. 处理HTTP-FLV格式的视频流请求
 * 2. 支持多客户端观看同一路流（流复用）
 * 3. 管理设备与转码任务的映射关系
 * 4. 处理客户端连接的生命周期
 * <p>
 * <p>
 * 继承关系：
 * SimpleChannelInboundHandler<FullHttpRequest> - Netty入站处理器，自动处理HTTP请求
 * <p>
 */
@Slf4j
@Service
@ChannelHandler.Sharable
public class LiveHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    /**
     * Spring事件发布器
     * 用于发布设备相关的事件（如设备上线、下线等）
     */
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 构造函数 - Spring依赖注入
     *
     * @param eventPublisher Spring事件发布器，用于发布应用事件
     */
    public LiveHandler(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * 通道注册事件处理
     * 当Channel被注册到EventLoop时调用
     *
     * @param ctx 通道上下文对象
     * @throws Exception 如果处理过程中发生异常
     */
    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        log.info("服务端：通道已注册: {}", ctx.channel().id());
        super.channelRegistered(ctx);
    }

    /**
     * 通道就绪事件处理
     * 当WebSocket连接建立成功后调用
     *
     * @param ctx 通道上下文对象
     * @throws Exception 如果处理过程中发生异常
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        log.info("服务端：连接建立: {}", ctx.channel().id());
    }

    /**
     * HTTP请求处理（核心方法）
     * <p>
     * 处理流程：
     * 1. 解析请求URI，验证路径是否为/live
     * 2. 提取deviceId和rtspUrl参数
     * 3. 发送FLV响应头
     * 4. 启动或复用转码任务
     * <p>
     * 请求示例：http://localhost:8888/live?deviceId=camera001&rtspUrl=rtsp://rtspstream:unqH65eDVr9KAxrLjxw4y@zephyr.rtsp.stream/movie
     *
     * @param ctx 通道上下文对象 - 包含Channel、Pipeline等信息
     * @param req 完整的HTTP请求对象 - 包含请求头、请求体、URI等
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {

        // 步骤1: 解析请求URI
        QueryStringDecoder decoder = new QueryStringDecoder(req.uri());

        // 步骤2: 验证请求路径，必须是/live
        if (!"/live".equals(decoder.path())) {
            log.warn("服务端：收到错误的请求路径: {}", decoder.path());
            sendError(ctx, HttpResponseStatus.BAD_REQUEST);
            return;
        }

        // 步骤3: 解析URL参数（deviceId 和 rtspUrl）
        List<String> deviceIdParams = decoder.parameters().get("deviceId");
        List<String> rtspUrlParams = decoder.parameters().get("rtspUrl");

        // 步骤4: 验证deviceId参数是否存在
        if (deviceIdParams == null || deviceIdParams.isEmpty()) {
            log.warn("服务端：请求缺少deviceId参数");
            sendError(ctx, HttpResponseStatus.BAD_REQUEST);
            return;
        }

        // 步骤5: 验证rtspUrl参数是否存在
        if (rtspUrlParams == null || rtspUrlParams.isEmpty()) {
            log.warn("服务端：请求缺少rtspUrl参数");
            sendError(ctx, HttpResponseStatus.BAD_REQUEST);
            return;
        }

        // 步骤6: 获取设备ID和RTSP地址
        String deviceId = deviceIdParams.getFirst();
        String rtspUrl = rtspUrlParams.getFirst();
        log.info("服务端：收到设备[{}]的直播请求，RTSP地址: {}", deviceId, rtspUrl);

        // 步骤7: 发送FLV格式的HTTP响应头
        sendFlvResHeader(ctx);

        // 步骤8: 创建设备对象
        Device device = new Device(deviceId, rtspUrl);

        // 步骤9: 启动播放（转码并推流）
        playForHttp(device, ctx);
    }

    /**
     * 启动HTTP-FLV播放（流复用核心逻辑）
     * <p>
     * 流复用机制：
     * 1. 首次请求：创建新的转码任务，启动FFmpeg转码线程
     * 2. 后续请求：复用已有的转码任务，只添加新的输出通道
     * <p>
     * 优势：
     * - 节省服务器资源（CPU、内存）
     * - 多个客户端共享同一路转码流
     * - 减少对RTSP源的访问压力
     *
     * @param device 设备对象 - 包含设备ID和RTSP源地址
     * @param ctx    通道上下文 - 用于向客户端发送FLV数据
     */
    public void playForHttp(Device device, ChannelHandlerContext ctx) {
        try {
            // 创建转码任务对象
            TransferToFlv mediaConvert = new TransferToFlv();

            // 【流复用检查】判断该设备是否已经有转码任务在运行
            if (deviceContext.containsKey(device.getDeviceId())) {
                log.info("服务端：设备[{}]已有转码任务，复用现有流", device.getDeviceId());

                // 获取已存在的转码任务
                mediaConvert = deviceContext.get(device.getDeviceId());

                // 将新客户端的通道添加到输出列表
                mediaConvert.getMediaChannel().addChannel(ctx, true);
                return;
            }

            // 【首次请求】创建新的转码任务
            log.info("服务端：设备[{}]首次请求，创建新的转码任务", device.getDeviceId());

            // 设置当前设备信息
            mediaConvert.setCurrentDevice(device);

            // 创建媒体通道管理器（管理多个客户端连接）
            MediaChannel mediaChannel = new MediaChannel(device, eventPublisher);
            mediaConvert.setMediaChannel(mediaChannel);

            // 将转码任务加入设备上下文映射表
            deviceContext.put(device.getDeviceId(), mediaConvert);

            // 启动转码线程（开始从RTSP拉流并转码为FLV）
            new Thread(mediaConvert, "TransferToFlv-" + device.getDeviceId()).start();

            // 将当前客户端通道添加到输出列表
            mediaConvert.getMediaChannel().addChannel(ctx, false);

            log.info("服务端：设备[{}]转码任务启动成功", device.getDeviceId());

        } catch (InterruptedException | FFmpegFrameRecorder.Exception e) {
            log.error("服务端：设备[{}]启动播放失败", device.getDeviceId(), e);
            throw new RuntimeException("启动HTTP-FLV播放失败: " + e.getMessage(), e);
        }
    }

    /**
     * 消息读取完成事件处理
     * 在一次消息读取操作完成后调用
     *
     * @param ctx 通道上下文对象
     * @throws Exception 如果处理过程中发生异常
     */
    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        // 刷新通道，确保所有写入的消息都立即发送出去
        ctx.flush();
        super.channelReadComplete(ctx);
    }

    /**
     * 异常处理
     * 处理连接过程中的异常情况
     *
     * @param ctx   通道上下文对象
     * @param cause 异常原因
     * @throws Exception 如果处理过程中发生异常
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("服务端：WebSocket连接异常: {}", cause.getMessage(), cause);
        ctx.close();
    }

    /**
     * 通道断开事件处理
     * 当WebSocket连接断开时调用
     *
     * @param ctx 通道上下文对象
     * @throws Exception 如果处理过程中发生异常
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.info("服务端：WebSocket连接断开: {}", ctx.channel().id());
    }


    /**
     * 发送错误响应给客户端
     * <p>
     * 处理流程：
     * 1. 构造HTTP错误响应（包含状态码和错误信息）
     * 2. 设置响应头Content-Type
     * 3. 发送响应并关闭连接
     *
     * @param ctx    通道上下文对象
     * @param status HTTP状态码（如400 BAD_REQUEST、404 NOT_FOUND等）
     */
    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
        // 构造完整的HTTP响应
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,  // HTTP版本
                status,                // HTTP状态码
                Unpooled.copiedBuffer("请求地址有误: " + status + "\r\n", CharsetUtil.UTF_8)  // 响应体
        );

        // 设置响应头：内容类型为纯文本，UTF-8编码
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");

        // 发送响应并在完成后关闭连接
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * 发送HTTP-FLV响应头
     * <p>
     * 作用：
     * 告知浏览器/播放器即将传输FLV格式的视频流
     * <p>
     * 响应头说明：
     * - Content-Type: video/x-flv - 标识FLV视频格式
     * - Connection: close - 传输完成后关闭连接
     * - Accept-Ranges: bytes - 支持断点续传
     * - Pragma/Cache-Control: no-cache - 禁用缓存，确保实时性
     * - Transfer-Encoding: chunked - 分块传输编码（流式传输）
     * <p>
     * 为什么使用chunked传输？
     * 因为直播流的长度是未知的，使用chunked可以持续发送数据
     *
     * @param ctx 通道上下文对象
     */
    private void sendFlvResHeader(ChannelHandlerContext ctx) {
        // 创建HTTP 200 OK响应
        HttpResponse rsp = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);

        // 设置响应头
        rsp.headers()
                // 连接类型：关闭
                .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
                // 内容类型：FLV视频格式
                .set(HttpHeaderNames.CONTENT_TYPE, "video/x-flv")
                // 支持范围请求（断点续传）
                .set(HttpHeaderNames.ACCEPT_RANGES, "bytes")
                // 禁用缓存（HTTP 1.0）
                .set(HttpHeaderNames.PRAGMA, "no-cache")
                // 禁用缓存（HTTP 1.1）
                .set(HttpHeaderNames.CACHE_CONTROL, "no-cache")
                // 分块传输编码（流式传输，长度未知）
                .set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
                // 服务器标识
                .set(HttpHeaderNames.SERVER, "Video-Stream-Middleware");

        // 发送响应头（不关闭连接，后续持续发送FLV数据）
        ctx.writeAndFlush(rsp);

        log.debug("服务端：已发送FLV响应头到客户端");
    }
}
