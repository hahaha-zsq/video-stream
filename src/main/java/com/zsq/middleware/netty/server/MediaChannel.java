package com.zsq.middleware.netty.server;

import com.zsq.middleware.model.Device;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;

import java.util.concurrent.*;

/**
 * 媒体通道管理类
 * <p>
 * 核心职责:
 * 1. 管理订阅同一设备流的所有 HTTP 客户端连接
 * 2. 实现多客户端广播推流(一对多)
 * 3. 定时检查客户端连接状态,无人观看时自动关闭流
 * 4. 处理 FLV 文件头的发送(新客户端加入时必须)
 * <p>
 * 设计特点:
 * - 使用 ConcurrentHashMap 线程安全地管理多个客户端
 * - 通过定时任务检测客户端连接,实现自动资源释放
 * - 发布 Spring 事件通知上层服务关闭流
 */
@Slf4j
@Data
public class MediaChannel {

    /**
     * 当前设备信息
     * 包含设备 ID 和 RTSP 流地址
     */
    private Device currentDevice;

    /**
     * HTTP 客户端连接集合
     * Key: Channel ID(唯一标识)
     * Value: Netty 通道上下文
     * 使用 ConcurrentHashMap 保证线程安全
     */
    private ConcurrentHashMap<String, ChannelHandlerContext> httpClients;

    /**
     * 定时检查任务的 Future 对象
     * 用于取消定时任务
     */
    private ScheduledFuture<?> checkFuture;

    /**
     * 定时任务调度器
     * 用于执行客户端连接检查任务
     */
    private final ScheduledExecutorService scheduler;

    /**
     * Spring 事件发布器
     * 用于发布流关闭事件
     */
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 流管理服务
     * 用于获取转码任务信息(如 FLV 文件头)
     */
    private final MediaStreamService streamService;

    /**
     * 构造方法
     * 初始化媒体通道
     *
     * @param currentDevice   当前设备信息
     * @param eventPublisher  Spring 事件发布器
     * @param streamService   流管理服务
     */
    public MediaChannel(Device currentDevice,
                        ApplicationEventPublisher eventPublisher,
                        MediaStreamService streamService) {
        this.currentDevice = currentDevice;
        this.httpClients = new ConcurrentHashMap<>();
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.eventPublisher = eventPublisher;
        this.streamService = streamService;
    }

    /**
     * 添加客户端连接到通道
     * <p>
     * 执行步骤:
     * 1. 检查通道是否可写
     * 2. 如果需要发送 FLV 头部,从 streamService 获取并发送
     * 3. 将客户端添加到 httpClients 集合
     * 4. 如果是第一个客户端,启动定时检查任务
     * <p>
     * 注意:
     * - needSendFlvHeader=true: 新客户端加入复用流时必须先发送 FLV 头
     * - needSendFlvHeader=false: 首个客户端,会在转码开始时自动收到头部
     *
     * @param ctx               Netty 通道上下文
     * @param needSendFlvHeader 是否需要发送 FLV 文件头
     * @throws InterruptedException          线程中断异常
     */
    public void addChannel(ChannelHandlerContext ctx, boolean needSendFlvHeader)
            throws InterruptedException {

        // 检查通道是否可写
        if (ctx.channel().isWritable()) {
            ChannelFuture future;
            // 如果需要发送 FLV 头部(新客户端加入时)
            if (needSendFlvHeader) {
                // 从 streamService 获取转码任务,然后提取 FLV 头部
                TransferToFlv task = streamService.getStreamTask(currentDevice.getDeviceId());
                byte[] flvHeader = (task != null) ? task.getFlvHeader() : null;

                // 发送 FLV 头部或空缓冲区
                if (flvHeader != null) {
                    future = ctx.writeAndFlush(Unpooled.copiedBuffer(flvHeader));
                } else {
                    future = ctx.writeAndFlush(Unpooled.EMPTY_BUFFER);
                }
            } else {
                // 首个客户端不需要发送头部,发送空缓冲区
                future = ctx.writeAndFlush(Unpooled.EMPTY_BUFFER);
            }

            // 异步等待发送完成,然后将客户端添加到集合
            future.addListener(f -> {
                if (f.isSuccess()) {
                    httpClients.put(ctx.channel().id().toString(), ctx);
                }
            });

            // 如果定时检查任务未启动,启动它
            // 每 10 秒检查一次客户端连接状态
            if (checkFuture == null || checkFuture.isCancelled()) {
                this.checkFuture = scheduler.scheduleAtFixedRate(
                        this::checkChannel, 0, 10, TimeUnit.SECONDS);
                log.info("设备[{}] 定时检查任务已启动", currentDevice.getDeviceId());
            }
        }
        // 稍微等待,确保操作完成
        Thread.sleep(50);
    }

    /**
     * 定时检查客户端连接状态
     * <p>
     * 执行逻辑:
     * 1. 检查 httpClients 集合是否为空
     * 2. 如果为空(所有客户端已断开),发布关闭流事件
     * 3. 取消定时检查任务
     * <p>
     * 设计理念:
     * - 通过 Spring 事件通知 MediaStreamService 关闭转码任务
     * - 实现无人观看时自动释放资源
     */
    private void checkChannel() {
        try {
            // 检查是否所有客户端已断开
            if (httpClients.isEmpty()) {
                log.info("设备[{}] 所有客户端已断开，发布关闭事件", currentDevice.getDeviceId());

                // 发布关闭流事件,由 MediaStreamService 监听并处理停止逻辑
                eventPublisher.publishEvent(new CloseStreamEvent(this, currentDevice));

                // 取消定时检查任务
                if (checkFuture != null) {
                    checkFuture.cancel(false);
                    checkFuture = null;
                }
            }
        } catch (Exception e) {
            log.error("设备[{}] 检查客户端连接状态时发生异常", currentDevice.getDeviceId(), e);
        }
    }

    /**
     * 关闭媒体通道
     * <p>
     * 执行步骤:
     * 1. 关闭所有客户端连接
     * 2. 清空客户端集合
     * 3. 取消定时检查任务
     * 4. 关闭调度器
     * <p>
     * 调用时机:
     * - 转码任务结束时
     * - 需要强制关闭流时
     */
    public void closeChannel() {
        // 关闭所有客户端连接
        httpClients.values().forEach(ctx -> {
            try {
                ctx.close();
            } catch (Exception e) {
                log.error("关闭客户端连接失败: {}", ctx.channel().id(), e);
            }
        });
        // 清空客户端集合
        httpClients.clear();

        // 取消定时检查任务
        if (checkFuture != null && !checkFuture.isCancelled()) {
            checkFuture.cancel(false);
        }
        // 关闭调度器
        if (!scheduler.isShutdown()) {
            scheduler.shutdown();
        }
    }

    /**
     * 向所有客户端广播发送数据
     * <p>
     * 执行逻辑:
     * 1. 遍历所有客户端
     * 2. 检查通道是否可写
     * 3. 可写则发送数据,不可写则移除该客户端
     * <p>
     * 调用时机:
     * - 转码后的 FLV 数据需要推送给客户端时
     *
     * @param data FLV 数据字节数组
     */
    public void sendData(byte[] data) {
        // 遍历所有客户端,广播发送数据
        httpClients.forEach((key, ctx) -> {
            // 检查通道是否可写
            if (ctx.channel().isWritable()) {
                // 可写则发送数据
                ctx.writeAndFlush(Unpooled.copiedBuffer(data));
            } else {
                // 不可写则移除该客户端(已断开或异常)
                httpClients.remove(key);
            }
        });
    }
}