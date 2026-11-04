package io.github.atengk.boot.ai.streaming;

import okhttp3.Call;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.Objects;

/**
 * OkHttp 流式连接安全取消拦截器
 * 拦截底层物理 HTTP 请求，获取 OkHttp Call 原生句柄并自动登记至 ActiveStreamRegistry；
 * 当客户端异常断连或主动终止时，通过 call.cancel() 迅速切断底层 TCP 套接字并排空资源
 *
 * @author Ateng
 * @since 2026-11-04
 */
public class StreamCancellationInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(StreamCancellationInterceptor.class);

    /**
     * 传递流式标识的 HTTP 请求头常量
     */
    public static final String HEADER_STREAM_ID = "X-Stream-Id";

    private final ActiveStreamRegistry registry;

    public StreamCancellationInterceptor(ActiveStreamRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "ActiveStreamRegistry 不能为空");
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        Call call = chain.call();

        // 1. 多层级提取 streamId (StreamContextHolder -> HTTP Header -> MDC traceId)
        String streamId = resolveStreamId(request);

        if (StringUtils.hasText(streamId)) {
            registry.register(streamId, () -> {
                log.info("收到流式连接中断信号，物理切断底层 OkHttp 套接字 [streamId={}]", streamId);
                call.cancel();
            });
        }

        try {
            return chain.proceed(request);
        } catch (IOException ex) {
            if (call.isCanceled()) {
                log.info("流式推流 OkHttp 物理连接已成功切断并安全排空 [streamId={}]", streamId);
            }
            throw ex;
        } finally {
            if (StringUtils.hasText(streamId)) {
                registry.unregister(streamId);
            }
        }
    }

    private String resolveStreamId(Request request) {
        String streamId = StreamContextHolder.getStreamId();
        if (StringUtils.hasText(streamId)) {
            return streamId.trim();
        }
        streamId = request.header(HEADER_STREAM_ID);
        if (StringUtils.hasText(streamId)) {
            return streamId.trim();
        }
        String traceId = MDC.get("traceId");
        if (StringUtils.hasText(traceId)) {
            return traceId.trim();
        }
        return null;
    }
}
