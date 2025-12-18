package com.zsq.middleware.server;

import com.zsq.middleware.model.NettyProperties;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.cors.CorsConfig;
import io.netty.handler.codec.http.cors.CorsConfigBuilder;
import io.netty.handler.codec.http.cors.CorsHandler;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * WebSocket通道初始化器
 * 
 * 该类负责为每个新的WebSocket连接配置处理器链（Pipeline），主要功能包括：
 * 1. SSL/TLS加密通信配置
 * 2. HTTP协议编解码
 * 3. WebSocket协议支持
 * 4. 心跳检测
 * 5. 消息压缩
 * 6. 业务逻辑处理
 * 
 * 处理器链配置顺序（从前到后）：
 * 1. SSL处理器（可选）
 * 2. HTTP编解码器
 * 3. HTTP消息聚合器
 * 4. 大文件传输处理器
 * 5. WebSocket压缩处理器
 * 6. WebSocket协议处理器
 * 7. 心跳检测处理器
 * 8. 业务逻辑处理器
 */
@Slf4j
@Component
public class NettyServerChannelInitializer extends ChannelInitializer<SocketChannel> {

    /**
     * WebSocket配置属性
     * 包含SSL配置、路径配置、心跳配置等
     */
    private final NettyProperties properties;

    private final LiveHandler liveHandler;

    /**
     * 构造函数
     *
     * @param properties WebSocket配置属性
     * @param liveHandler WebSocket消息处理器
     */
    public NettyServerChannelInitializer(NettyProperties properties, LiveHandler liveHandler) {
        this.properties = properties;
        this.liveHandler = liveHandler;
    }



    /**
     * 初始化WebSocket通道
     * 为每个新的客户端连接配置处理器链
     * 
     * 处理器配置说明：
     * 2. HttpServerCodec: HTTP请求解码和响应编码
     * 3. HttpObjectAggregator: 将HTTP消息的多个部分合并
     * 4. ChunkedWriteHandler: 支持大文件传输
     * 5. WebSocketServerCompressionHandler: WebSocket消息压缩
     * 6. WebSocketServerProtocolHandler: WebSocket协议处理
     * 8. LiveHandler: 业务逻辑处理
     *
     * @param ch 新建立的客户端连接通道
     * @throws Exception 初始化过程中发生异常
     */
    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        // 创建CORS配置
        CorsConfig corsConfig = CorsConfigBuilder.forAnyOrigin().allowNullOrigin().allowCredentials().build();
        ChannelPipeline pipeline = ch.pipeline();

       /*
        粘包就是多个数据混淆在一起了，而且多个数据包之间没有明确的分隔，导致无法对这些数据包进行正确的读取。
        半包就是一个大的数据包被拆分成了多个数据包发送，读取的时候没有把多个包合成一个原本的大包，导致读取的数据不完整。
        在纯 TCP 协议中容易出现，比如你自己写的 Netty + 二进制协议，就必须使用 LengthFieldBasedFrameDecoder 等解码器处理。
        */

        // HTTP编解码器 把字节流解码成 HTTP 请求对象（包括 headers + body）===>HTTP 是有消息边界的，能自然避免粘包
        pipeline.addLast(new HttpServerCodec());

        // HTTP对象聚合器，将多个HTTP消息聚合成一个完整的HTTP消息  ===>彻底消除了半包问题（最大帧可配置）
        pipeline.addLast(new HttpObjectAggregator(properties.getServer().getMaxFrameSize()));

        // 用于处理大文件传输（如发送大图）  ===>WebSocket 基于帧（frame）协议，有边界，不存在粘包问题
        pipeline.addLast(new ChunkedWriteHandler());

        // WebSocket消息压缩处理器
        pipeline.addLast(new WebSocketServerCompressionHandler());
        // CORS处理器
        pipeline.addLast(new CorsHandler(corsConfig));

        // 业务逻辑处理器
        pipeline.addLast(liveHandler);

        log.debug("服务端：WebSocket通道初始化完成: {}", ch.id());
    }
}

