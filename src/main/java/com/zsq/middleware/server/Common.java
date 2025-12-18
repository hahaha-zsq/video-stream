package com.zsq.middleware.server;

import java.util.concurrent.ConcurrentHashMap;

public class Common {
    /**
     * 设备上下文映射表（核心数据结构）
     * <p>
     * Key: 设备ID（deviceId）
     * Value: TransferToFlv转码任务对象
     * <p>
     * 作用：
     * 1. 实现流复用 - 多个客户端请求同一设备时，共享同一个转码任务
     * 2. 管理转码任务的生命周期
     * 3. 线程安全 - 使用ConcurrentHashMap支持并发访问
     */
    public static ConcurrentHashMap<String, TransferToFlv> deviceContext = new ConcurrentHashMap<>();
}
