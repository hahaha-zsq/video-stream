package com.zsq.middleware.server;

import com.zsq.middleware.model.Device;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.springframework.context.ApplicationEventPublisher;

import java.util.concurrent.*;

@Slf4j
@Data
public class MediaChannel {

    private Device currentDevice;
    private ConcurrentHashMap<String, ChannelHandlerContext> httpClients;
    private ScheduledFuture<?> checkFuture;
    private final ScheduledExecutorService scheduler;
    private final ApplicationEventPublisher eventPublisher;

    // 新增：引入 Service 用于获取任务信息
    private final MediaStreamService streamService;

    public MediaChannel(Device currentDevice,
                        ApplicationEventPublisher eventPublisher,
                        MediaStreamService streamService) {
        this.currentDevice = currentDevice;
        this.httpClients = new ConcurrentHashMap<>();
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.eventPublisher = eventPublisher;
        this.streamService = streamService;
    }

    public void addChannel(ChannelHandlerContext ctx, boolean needSendFlvHeader)
            throws InterruptedException, FFmpegFrameRecorder.Exception {

        if (ctx.channel().isWritable()) {
            ChannelFuture future;
            if (needSendFlvHeader) {
                // 修改点：通过 Service 获取 FLV 头部，而不是静态 Map
                TransferToFlv task = streamService.getStreamTask(currentDevice.getDeviceId());
                byte[] flvHeader = (task != null) ? task.getFlvHeader() : null;

                if (flvHeader != null) {
                    future = ctx.writeAndFlush(Unpooled.copiedBuffer(flvHeader));
                } else {
                    future = ctx.writeAndFlush(Unpooled.EMPTY_BUFFER);
                }
            } else {
                future = ctx.writeAndFlush(Unpooled.EMPTY_BUFFER);
            }

            future.addListener(f -> {
                if (f.isSuccess()) {
                    httpClients.put(ctx.channel().id().toString(), ctx);
                }
            });

            if (checkFuture == null || checkFuture.isCancelled()) {
                this.checkFuture = scheduler.scheduleAtFixedRate(
                        this::checkChannel, 0, 10, TimeUnit.SECONDS);
                log.info("设备[{}] 定时检查任务已启动", currentDevice.getDeviceId());
            }
        }
        Thread.sleep(50);
    }

    private void checkChannel() {
        try {
            if (httpClients.isEmpty()) {
                log.info("设备[{}] 所有客户端已断开，发布关闭事件", currentDevice.getDeviceId());

                // 发布事件，由 MediaStreamService 监听并处理停止逻辑
                eventPublisher.publishEvent(new CloseStreamEvent(this, currentDevice));

                if (checkFuture != null) {
                    checkFuture.cancel(false);
                    checkFuture = null;
                }
            }
        } catch (Exception e) {
            log.error("设备[{}] 检查客户端连接状态时发生异常", currentDevice.getDeviceId(), e);
        }
    }

    public void closeChannel() {
        httpClients.values().forEach(ctx -> {
            try {
                ctx.close();
            } catch (Exception e) {
                log.error("关闭客户端连接失败: {}", ctx.channel().id(), e);
            }
        });
        httpClients.clear();

        if (checkFuture != null && !checkFuture.isCancelled()) {
            checkFuture.cancel(false);
        }
        if (!scheduler.isShutdown()) {
            scheduler.shutdown();
        }
    }

    public void sendData(byte[] data) {
        httpClients.forEach((key, ctx) -> {
            if (ctx.channel().isWritable()) {
                ctx.writeAndFlush(Unpooled.copiedBuffer(data));
            } else {
                httpClients.remove(key);
            }
        });
    }
}