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

import static com.zsq.middleware.server.Common.deviceContext;

/**
 * 媒体通道管理类
 * 负责管理单个设备的所有HTTP客户端连接，处理视频流数据的分发
 */
@Slf4j
@Data
public class MediaChannel {

    /**
     * 当前设备信息
     */
    private Device currentDevice;

    /**
     * HTTP客户端连接集合
     * key: 通道ID, value: 通道上下文
     */
    private ConcurrentHashMap<String, ChannelHandlerContext> httpClients;

    /**
     * 定时检查任务的Future对象，用于取消任务
     */
    private ScheduledFuture<?> checkFuture;

    /**
     * 定时任务调度器，用于定期检查客户端连接状态
     */
    private final ScheduledExecutorService scheduler;

    /**
     * Spring事件发布器，用于发布关闭流事件
     */
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 构造函数
     *
     * @param currentDevice  当前设备信息
     * @param eventPublisher Spring事件发布器
     */
    public MediaChannel(Device currentDevice, ApplicationEventPublisher eventPublisher) {
        this.currentDevice = currentDevice;
        this.httpClients = new ConcurrentHashMap<>();
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.eventPublisher = eventPublisher;
    }

    /**
     * 添加HTTP客户端通道
     *
     * @param ctx               Netty通道上下文
     * @param needSendFlvHeader 是否需要发送FLV文件头
     * @throws InterruptedException          线程中断异常
     * @throws FFmpegFrameRecorder.Exception FFmpeg录制异常
     */
    public void addChannel(ChannelHandlerContext ctx, boolean needSendFlvHeader)
            throws InterruptedException, FFmpegFrameRecorder.Exception {

        // 检查通道是否可写
        if (ctx.channel().isWritable()) {
            ChannelFuture future;
            // 根据需要发送FLV头部信息，首次创建任务为false,发送一个空缓冲区（相当于占位）,因为此时转码器还未启动，FLV头部还未生成
            if (needSendFlvHeader) {
                // 从设备上下文中获取FLV头部数据
                byte[] flvHeader = deviceContext
                        .get(currentDevice.getDeviceId())
                        .getFlvHeader();
                // 发送FLV头部
                future = ctx.writeAndFlush(Unpooled.copiedBuffer(flvHeader));
            } else {
                // 发送空缓冲区
                future = ctx.writeAndFlush(Unpooled.EMPTY_BUFFER);
            }

            // 添加异步监听器，写入成功后将通道添加到客户端集合
            future.addListener(f -> {
                if (f.isSuccess()) {
                    httpClients.put(ctx.channel().id().toString(), ctx);
                }
            });
            // 只在第一次添加客户端时启动定时检查任务
            if (checkFuture == null || checkFuture.isCancelled()) {
                this.checkFuture = scheduler.scheduleAtFixedRate(
                        this::checkChannel, 0, 10, TimeUnit.SECONDS);
                log.info("设备[{}] 定时检查任务已启动", currentDevice.getDeviceId());
            }
        }
        // 短暂休眠，避免过快操作
        Thread.sleep(50);
    }

    /**
     * 定时检查客户端连接状态
     * 当所有客户端断开连接时(一个客户端都没有连接时)，发布关闭流事件并关闭调度器
     */
    private void checkChannel() {
        try {
            if (httpClients.isEmpty()) {
                log.info("设备[{}] 所有客户端已断开，准备关闭推流", currentDevice.getDeviceId());

                // 发布关闭流事件，通知 TransferToFlv 停止运行
                eventPublisher.publishEvent(
                        new CloseStreamEvent(this, currentDevice)
                );

                // 仅取消当前的定时任务，保持 scheduler 存活以备可能的复用
                if (checkFuture != null) {
                    checkFuture.cancel(false);
                    checkFuture = null; // 建议置空，方便 addChannel 判断
                }
            }
        } catch (Exception e) {
            log.error("设备[{}] 检查客户端连接状态时发生异常", currentDevice.getDeviceId(), e);
        }
    }

    /**
     * 关闭所有客户端连接
     */
    public void closeChannel() {
        httpClients.values().forEach(ctx -> {
            try {
                ctx.close();
            } catch (Exception e) {
                log.error("关闭客户端连接失败: {}", ctx.channel().id(), e);
            }
        });
        httpClients.clear();

        // 确保调度器被关闭
        if (checkFuture != null && !checkFuture.isCancelled()) {
            checkFuture.cancel(false);
        }
        if (!scheduler.isShutdown()) {
            scheduler.shutdown();
        }
    }

    /**
     * 向所有客户端发送数据
     *
     * @param data 要发送的视频流数据
     */
    public void sendData(byte[] data) {
        // 遍历所有客户端连接
        httpClients.forEach((key, ctx) -> {
            // 检查通道是否可写
            if (ctx.channel().isWritable()) {
                // 发送数据
                ctx.writeAndFlush(Unpooled.copiedBuffer(data));
            } else {
                // 通道不可写，移除该客户端
                httpClients.remove(key);
            }
        });
    }
}
