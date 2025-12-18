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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@ChannelHandler.Sharable
public class LiveHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final ApplicationEventPublisher eventPublisher;
    // 新增：注入流管理服务
    private final MediaStreamService streamService;

    public LiveHandler(ApplicationEventPublisher eventPublisher,
                       MediaStreamService streamService) {
        this.eventPublisher = eventPublisher;
        this.streamService = streamService;
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        QueryStringDecoder decoder = new QueryStringDecoder(req.uri());
        if (!"/live".equals(decoder.path())) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST);
            return;
        }

        List<String> deviceIdParams = decoder.parameters().get("deviceId");
        List<String> rtspUrlParams = decoder.parameters().get("rtspUrl");

        if (deviceIdParams == null || deviceIdParams.isEmpty() || rtspUrlParams == null || rtspUrlParams.isEmpty()) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST);
            return;
        }

        String deviceId = deviceIdParams.getFirst();
        String rtspUrl = rtspUrlParams.getFirst();

        sendFlvResHeader(ctx);

        Device device = new Device(deviceId, rtspUrl);
        playForHttp(device, ctx);
    }

    public void playForHttp(Device device, ChannelHandlerContext ctx) {
        try {
            // 1. 尝试从 Service 获取现有任务
            TransferToFlv mediaConvert = streamService.getStreamTask(device.getDeviceId());

            // 2. 如果存在，复用流
            if (mediaConvert != null) {
                log.info("服务端：设备[{}] 已有转码任务，复用现有流", device.getDeviceId());
                mediaConvert.getMediaChannel().addChannel(ctx, true);
                return;
            }

            // 3. 如果不存在，创建新任务
            log.info("服务端：设备[{}] 首次请求，创建新的转码任务", device.getDeviceId());
            mediaConvert = new TransferToFlv();
            mediaConvert.setCurrentDevice(device);

            // 4. 创建 MediaChannel，传入 streamService
            MediaChannel mediaChannel = new MediaChannel(device, eventPublisher, streamService);
            mediaConvert.setMediaChannel(mediaChannel);

            // 5. 注册任务到 Service
            streamService.registerTask(device, mediaConvert);

            // 6. 启动线程
            new Thread(mediaConvert, "TransferToFlv-" + device.getDeviceId()).start();

            // 7. 添加客户端
            mediaConvert.getMediaChannel().addChannel(ctx, false);
            log.info("服务端：设备[{}] 转码任务启动成功", device.getDeviceId());

        } catch (Exception e) {
            log.error("服务端：设备[{}] 启动播放失败", device.getDeviceId(), e);
            throw new RuntimeException("启动播放失败", e);
        }
    }

    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status,
                Unpooled.copiedBuffer("Error: " + status + "\r\n", CharsetUtil.UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private void sendFlvResHeader(ChannelHandlerContext ctx) {
        HttpResponse rsp = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        rsp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
                .set(HttpHeaderNames.CONTENT_TYPE, "video/x-flv")
                .set(HttpHeaderNames.ACCEPT_RANGES, "bytes")
                .set(HttpHeaderNames.PRAGMA, "no-cache")
                .set(HttpHeaderNames.CACHE_CONTROL, "no-cache")
                .set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
                .set(HttpHeaderNames.SERVER, "Video-Stream-Middleware");
        ctx.writeAndFlush(rsp);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("连接异常", cause);
        ctx.close();
    }
}