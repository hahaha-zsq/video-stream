package com.zsq.middleware;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 视频流转码中间件主程序
 * 
 * @author zsq
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class VideoStreamMiddlewareApplication {

    public static void main(String[] args) {
        SpringApplication.run(VideoStreamMiddlewareApplication.class, args);
    }
}
