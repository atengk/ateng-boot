package io.github.atengk.boot.milvus.client;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 线程上下文感知的 Milvus gRPC 动态路由与单次 RPC 超时拦截器
 * 按每次 RPC 调用实时解析生效数据库并覆写单一 {@code dbname} 请求头，同时注入单次调用超时阈值防无界挂起
 *
 * @author Ateng
 * @since 2026-11-20
 */
public class MilvusDynamicRpcInterceptor implements ClientInterceptor {

    public static final Metadata.Key<String> DB_NAME_HEADER_KEY =
            Metadata.Key.of("dbname", Metadata.ASCII_STRING_MARSHALLER);

    private final Supplier<String> databaseResolver;
    private final long rpcDeadlineMs;

    public MilvusDynamicRpcInterceptor(Supplier<String> databaseResolver, long rpcDeadlineMs) {
        Assert.notNull(databaseResolver, "databaseResolver must not be null");
        this.databaseResolver = databaseResolver;
        this.rpcDeadlineMs = rpcDeadlineMs;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
                                                               CallOptions callOptions,
                                                               Channel next) {
        CallOptions effectiveOptions = (rpcDeadlineMs > 0 && callOptions.getDeadline() == null)
                ? callOptions.withDeadlineAfter(rpcDeadlineMs, TimeUnit.MILLISECONDS)
                : callOptions;
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, effectiveOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                String targetDb = databaseResolver.get();
                // 清理所有历史或上游残留的 dbname 头，确保出站元数据仅保留单一当前生效库名
                headers.discardAll(DB_NAME_HEADER_KEY);
                if (StringUtils.hasText(targetDb)) {
                    headers.put(DB_NAME_HEADER_KEY, targetDb.trim());
                }
                super.start(responseListener, headers);
            }
        };
    }
}
