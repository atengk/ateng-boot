package io.github.atengk.boot.ai.streaming;

import okhttp3.Call;
import okhttp3.EventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.Objects;

/**
 * OkHttp 流式调用事件监听器
 * 在 OkHttp Call 初始化与启动的第一时间（同步阻塞于主线程 enqueue 调用时）绑定物理句柄，防御超早期断连竞态
 *
 * @author Ateng
 * @since 2026-11-04
 */
public class StreamEventListener extends EventListener {

    private static final Logger log = LoggerFactory.getLogger(StreamEventListener.class);

    private final ActiveStreamRegistry registry;

    public StreamEventListener(ActiveStreamRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "ActiveStreamRegistry 不能为空");
    }

    @Override
    public void callStart(Call call) {
        String streamId = StreamContextHolder.getStreamId();
        if (StringUtils.hasText(streamId)) {
            registry.register(streamId, () -> {
                log.info("EventListener 触发物理切断 OkHttp 套接字 [streamId={}]", streamId);
                call.cancel();
            });
        }
    }

    @Override
    public void callEnd(Call call) {
        String streamId = StreamContextHolder.getStreamId();
        if (StringUtils.hasText(streamId)) {
            registry.unregister(streamId);
        }
    }

    @Override
    public void callFailed(Call call, IOException ioe) {
        String streamId = StreamContextHolder.getStreamId();
        if (StringUtils.hasText(streamId)) {
            registry.unregister(streamId);
        }
    }
}
