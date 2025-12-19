package com.zsq.middleware.config;

import com.zsq.middleware.model.NettyProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class Config {
    @Bean("winterNettyServerTaskExecutor")
    public ThreadPoolTaskExecutor winterNettyServerTaskExecutor(NettyProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        NettyProperties.ThreadProperties threadProps = properties.getServer().getThreadPool();
        // 设置核心线程数：线程池创建时的初始线程数
        executor.setCorePoolSize(threadProps.getCorePoolSize());
        // 设置最大线程数：当任务队列满时，可以创建的最大线程数
        executor.setMaxPoolSize(threadProps.getMaxPoolSize());
        // 设置任务队列容量：当核心线程都在工作时，新任务会放入队列等待
        executor.setQueueCapacity(threadProps.getQueueCapacity());
        // 线程空闲超时时间：超过核心线程数的线程如果空闲，会在指定时间后被回收
        executor.setKeepAliveSeconds(threadProps.getKeepAliveSeconds());
        // 设置线程名称前缀：便于在日志中识别线程来源
        executor.setThreadNamePrefix(threadProps.getNamePrefix() + "server");
        // 设置关闭策略：是否等待所有任务完成后再关闭线程池
        executor.setWaitForTasksToCompleteOnShutdown(threadProps.getWaitForTasksToCompleteOnShutdown());
        // 设置关闭前等待时间：在关闭线程池时，等待指定秒数让任务完成
        executor.setAwaitTerminationSeconds(threadProps.getAwaitTerminationSeconds());
        //  设置拒绝策略：当任务队列满时，新任务会执行拒绝策略
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        executor.initialize();

        return executor;
    }

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();

        // 允许所有来源（生产环境建议配置具体域名）
        config.addAllowedOriginPattern("*");

        // 允许所有请求头
        config.addAllowedHeader("*");

        // 允许所有请求方法
        config.addAllowedMethod("*");

        // 允许携带凭证（Cookie）
        config.setAllowCredentials(true);

        // 预检请求的有效期（秒）
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // 对所有路径应用 CORS 配置
        source.registerCorsConfiguration("/**", config);

        return new CorsFilter(source);
    }
}
