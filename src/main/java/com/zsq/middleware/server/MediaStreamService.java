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
 * 核心职责:
 * 1. 统一管理所有 TransferToFlv 转码任务的生命周期
 * 2. 提供任务注册、查询、停止等核心功能
 * 3. 监听流关闭事件,实现无人观看时自动释放资源
 * 4. 替代原有的静态 Map,实现更好的解耦和可测试性
 * <p>
 * 设计特点:
 * - 作为 Spring Service 单例,全局唯一
 * - 使用 ConcurrentHashMap 保证线程安全
 * - 通过 Spring 事件机制实现组件解耦
 * - 支持流复用:多客户端请求同一设备时共享同一转码任务
 * <p>
 * 工作流程:
 * 1. LiveHandler 创建新任务后调用 registerTask() 注册
 * 2. 后续客户端通过 getStreamTask() 获取任务实现复用
 * 3. MediaChannel 检测到无人观看时发布 CloseStreamEvent
 * 4. 本服务监听事件,移除任务并调用 stop() 停止转码
 */
@Slf4j
@Service
public class MediaStreamService {

    /**
     * 转码任务缓存
     * <p>
     * Key: deviceId (设备唯一标识)
     * Value: TransferToFlv 转码任务对象
     * <p>
     * 使用 ConcurrentHashMap 保证线程安全
     * 支持多客户端并发请求同一设备时的任务复用
     */
    private final ConcurrentHashMap<String, TransferToFlv> streamTasks = new ConcurrentHashMap<>();

    /**
     * 获取已存在的转码任务
     * <p>
     * 调用时机:
     * - 新客户端请求时,检查是否可以复用现有流
     * - MediaChannel 需要获取 FLV 文件头时
     *
     * @param deviceId 设备 ID
     * @return 转码任务对象,如果不存在则返回 null
     */
    public TransferToFlv getStreamTask(String deviceId) {
        return streamTasks.get(deviceId);
    }

    /**
     * 注册新的转码任务
     * <p>
     * 执行逻辑:
     * 1. 将任务对象存入 streamTasks Map
     * 2. 记录注册日志
     * <p>
     * 调用时机:
     * - LiveHandler 创建新的转码任务后,需要注册以便后续复用
     *
     * @param device 设备信息
     * @param task   转码任务对象
     */
    public void registerTask(Device device, TransferToFlv task) {
        streamTasks.put(device.getDeviceId(), task);
        log.info("流管理:任务已注册 deviceId={}", device.getDeviceId());
    }

    /**
     * 监听流关闭事件
     * <p>
     * 事件源:
     * - MediaChannel 检测到所有客户端已断开时发出
     * <p>
     * 执行逻辑:
     * 1. 从 streamTasks Map 中移除任务(防止后续请求复用即将关闭的流)
     * 2. 调用任务的 stop() 方法,优雅停止转码线程
     * 3. 记录关闭日志
     * <p>
     * 设计理念:
     * - 因为本类是 Spring 单例 Bean,所以 @EventListener 一定生效
     * - 通过事件机制解耦 MediaChannel 和 MediaStreamService
     * - 实现无人观看时自动释放资源
     *
     * @param event 流关闭事件
     */
    @EventListener
    public void handleCloseStreamEvent(CloseStreamEvent event) {
        String deviceId = event.getDevice().getDeviceId();
        log.info("流管理:收到关闭事件,准备停止任务 deviceId={}", deviceId);

        // 步骤1: 从 Map 中移除任务(防止后续请求复用这个即将关闭的流)
        TransferToFlv task = streamTasks.remove(deviceId);

        // 步骤2: 调用任务的停止方法,优雅停止转码线程
        if (task != null) {
            task.stop();
            log.info("流管理:任务已停止并移除 deviceId={}", deviceId);
        } else {
            log.warn("流管理:未找到对应的任务,可能已关闭 deviceId={}", deviceId);
        }
    }
}