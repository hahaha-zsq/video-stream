package com.zsq.middleware.server;

import com.zsq.middleware.model.Device;
import com.zsq.middleware.server.CloseStreamEvent;
import com.zsq.middleware.server.TransferToFlv;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 流媒体管理服务
 * <p>
 * 核心职责：
 * 1. 统一管理所有 TransferToFlv 转码任务的生命周期
 * 2. 替代原有的 Common.deviceContext 静态 Map
 * 3. 监听流关闭事件，确保任务正确停止
 */
@Slf4j
@Service
public class MediaStreamService {

    /**
     * 转码任务缓存
     * Key: deviceId
     * Value: 转码任务对象
     */
    private final ConcurrentHashMap<String, TransferToFlv> streamTasks = new ConcurrentHashMap<>();

    /**
     * 获取已存在的任务
     */
    public TransferToFlv getStreamTask(String deviceId) {
        return streamTasks.get(deviceId);
    }

    /**
     * 注册新任务
     */
    public void registerTask(Device device, TransferToFlv task) {
        streamTasks.put(device.getDeviceId(), task);
        log.info("流管理：任务已注册 deviceId={}", device.getDeviceId());
    }

    /**
     * 监听流关闭事件
     * <p>
     * 当 MediaChannel 检测到无人观看时发出此事件。
     * 因为本类是 Spring 单例 Bean，所以 @EventListener 一定生效。
     */
    @EventListener
    public void handleCloseStreamEvent(CloseStreamEvent event) {
        String deviceId = event.getDevice().getDeviceId();
        log.info("流管理：收到关闭事件，准备停止任务 deviceId={}", deviceId);

        // 1. 从 Map 中移除（防止后续请求复用这个即将关闭的流）
        TransferToFlv task = streamTasks.remove(deviceId);

        // 2. 调用任务的停止方法
        if (task != null) {
            task.stop();
            log.info("流管理：任务已停止并移除 deviceId={}", deviceId);
        } else {
            log.warn("流管理：未找到对应的任务，可能已关闭 deviceId={}", deviceId);
        }
    }
}