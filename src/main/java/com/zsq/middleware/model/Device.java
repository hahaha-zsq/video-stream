package com.zsq.middleware.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 设备模型类
 * 表示一个视频设备（摄像头）及其RTSP流地址
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Device {

   /**
    * 设备ID（唯一标识）
    */
   private String deviceId;

   /**
    * RTSP流地址
    * 示例：rtsp://admin:password@192.168.1.100:554/stream1
    */
   private String rtspUrl;

}