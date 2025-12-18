package com.zsq.middleware;

import com.zsq.middleware.enums.StreamProtocol;
import com.zsq.middleware.manager.StreamManager;
import com.zsq.middleware.model.StreamInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 流管理器测试
 */
@SpringBootTest
class StreamManagerTest {

    @Autowired
    private StreamManager streamManager;

    @Test
    void testStreamCreation() {
        // 测试流创建
        String sourceUrl = "rtsp://example.com/test";
        StreamProtocol protocol = StreamProtocol.HLS;
        
        StreamInfo stream = streamManager.getOrCreateStream(sourceUrl, protocol, null);
        
        assertNotNull(stream);
        assertNotNull(stream.getStreamId());
        assertEquals(sourceUrl, stream.getSourceUrl());
        assertEquals(protocol, stream.getTargetProtocol());
        assertEquals(1, stream.getClientCount().get());
    }

    @Test
    void testStreamReuse() {
        // 测试流复用
        String sourceUrl = "rtsp://example.com/test2";
        StreamProtocol protocol = StreamProtocol.HLS;
        
        StreamInfo stream1 = streamManager.getOrCreateStream(sourceUrl, protocol, null);
        StreamInfo stream2 = streamManager.getOrCreateStream(sourceUrl, protocol, null);
        
        // 应该是同一个流对象
        assertEquals(stream1.getStreamId(), stream2.getStreamId());
        assertEquals(2, stream1.getClientCount().get());
    }

    @Test
    void testStreamStatistics() {
        // 测试统计信息
        var stats = streamManager.getStatistics();
        
        assertNotNull(stats);
        assertTrue(stats.containsKey("totalStreams"));
        assertTrue(stats.containsKey("activeStreams"));
    }
}
