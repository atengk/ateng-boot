package io.github.atengk.boot.milvus.autoconfigure;

import io.github.atengk.boot.milvus.client.MilvusDynamicRpcInterceptor;
import io.github.atengk.boot.milvus.exception.MilvusException;
import io.github.atengk.boot.milvus.properties.AtengMilvusProperties;
import io.grpc.ManagedChannel;
import io.milvus.v2.client.ConnectConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Milvus 弹性降级构建异常通道释放专项测试
 *
 * @author Ateng
 * @since 2026-11-20
 */
class AtengMilvusResilientChannelTest {

    @Test
    @DisplayName("buildResilientFallbackClient - 初始化异常分支确定性关闭已创建的 ManagedChannel")
    void testResilientFallbackClosesChannelOnBinderFailure() {
        AtengMilvusAutoConfiguration config = new AtengMilvusAutoConfiguration();
        AtengMilvusProperties properties = new AtengMilvusProperties();
        ConnectConfig connectConfig = ConnectConfig.builder()
                .uri(properties.resolveEffectiveUri())
                .dbName("default")
                .rpcDeadlineMs(30_000L)
                .build();

        ManagedChannel createdChannel = Mockito.mock(ManagedChannel.class);
        when(createdChannel.isShutdown()).thenReturn(false);

        MilvusDynamicRpcInterceptor interceptor = new MilvusDynamicRpcInterceptor(() -> "default", 30_000L);

        assertThatThrownBy(() -> config.buildResilientFallbackClient(
                connectConfig,
                interceptor,
                "default",
                cfg -> createdChannel,
                (ch, intercepted, stub, cfg, db) -> {
                    throw new IllegalStateException("模拟客户端视图绑定失败");
                }))
                .isInstanceOf(MilvusException.class);

        verify(createdChannel).shutdown();
    }
}
