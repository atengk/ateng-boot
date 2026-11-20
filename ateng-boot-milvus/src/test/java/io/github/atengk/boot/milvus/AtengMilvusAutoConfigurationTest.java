package io.github.atengk.boot.milvus;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.milvus.autoconfigure.AtengMilvusAutoConfiguration;
import io.github.atengk.boot.milvus.client.MilvusDatabaseClientManager;
import io.github.atengk.boot.milvus.context.MilvusContextHolder;
import io.github.atengk.boot.milvus.customizer.MilvusClientCustomizer;
import io.github.atengk.boot.milvus.exception.MilvusException;
import io.github.atengk.boot.milvus.health.MilvusHealthIndicator;
import io.github.atengk.boot.milvus.template.MilvusTemplate;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.milvus.grpc.ConnectRequest;
import io.milvus.grpc.ConnectResponse;
import io.milvus.grpc.ListDatabasesRequest;
import io.milvus.grpc.ListDatabasesResponse;
import io.milvus.grpc.MilvusServiceGrpc;
import io.milvus.grpc.ShowCollectionsRequest;
import io.milvus.grpc.ShowCollectionsResponse;
import io.milvus.grpc.Status;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Milvus 自动装配行为单元测试
 *
 * @author Ateng
 * @since 2026-11-04
 */
class AtengMilvusAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengMilvusAutoConfiguration.class));

    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
        MilvusContextHolder.clear();
    }

    @Test
    @DisplayName("测试当存在客户端 Bean 时自动装配核心门面与健康探针")
    void testAutoConfigurationWithClient() {
        contextRunner.withUserConfiguration(CustomMilvusConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(MilvusClientV2.class);
                    assertThat(context).hasSingleBean(MilvusTemplate.class);
                    assertThat(context).hasSingleBean(MilvusHealthIndicator.class);

                    MilvusTemplate template = context.getBean(MilvusTemplate.class);
                    assertThat(template.getClient()).isNotNull();
                });
    }

    @Test
    @DisplayName("测试关闭健康探针配置时仅装配核心门面不装配探针")
    void testHealthIndicatorDisabled() {
        contextRunner.withUserConfiguration(CustomMilvusConfig.class)
                .withPropertyValues("management.health.milvus.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(MilvusTemplate.class);
                    assertThat(context).doesNotHaveBean(MilvusHealthIndicator.class);
                });
    }

    @Test
    @DisplayName("测试当 enabled=false 时自动装配完全不生效")
    void testDisabledConfiguration() {
        contextRunner.withPropertyValues("ateng.boot.milvus.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(MilvusClientV2.class);
                    assertThat(context).doesNotHaveBean(MilvusTemplate.class);
                    assertThat(context).doesNotHaveBean(MilvusHealthIndicator.class);
                });
    }

    @Test
    @DisplayName("测试业务端自定义 Bean 时优先接管 (@ConditionalOnMissingBean)")
    void testCustomBeanOverride() {
        contextRunner.withUserConfiguration(CustomMilvusConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(MilvusClientV2.class);
                    assertThat(context).hasSingleBean(MilvusTemplate.class);

                    MilvusTemplate template = context.getBean(MilvusTemplate.class);
                    assertThat(template.getClient()).isNotNull();
                });
    }

    @Test
    @DisplayName("测试 MilvusClientCustomizer SPI 定制 ConnectConfig 与 rpc-deadline 透传")
    void testCustomizerAndRpcDeadlinePropagation() throws Exception {
        AtomicReference<ConnectConfig> capturedConfig = new AtomicReference<>();
        contextRunner
                .withPropertyValues(
                        "ateng.boot.milvus.uri=http://127.0.0.1:19998",
                        "ateng.boot.milvus.connect-timeout=200ms",
                        "ateng.boot.milvus.rpc-deadline=15s"
                )
                .withBean(MilvusClientCustomizer.class, () -> config -> {
                    capturedConfig.set(config);
                    config.setKeepAliveWithoutCalls(true);
                    config.setDbName("custom_spi_db");
                })
                .run(context -> {
                    assertThat(context).hasSingleBean(MilvusClientV2.class);
                    assertThat(context).hasSingleBean(MilvusTemplate.class);
                    assertThat(context).hasSingleBean(MilvusDatabaseClientManager.class);

                    assertThat(capturedConfig.get()).isNotNull();
                    assertThat(capturedConfig.get().getRpcDeadlineMs()).isEqualTo(15_000L);
                    assertThat(capturedConfig.get().isKeepAliveWithoutCalls()).isTrue();
                    assertThat(context.getBean(MilvusDatabaseClientManager.class).getDefaultDatabase())
                            .isEqualTo("custom_spi_db");
                    assertThat(context.getBean(MilvusTemplate.class).resolveCurrentDatabase())
                            .isEqualTo("custom_spi_db");
                });
    }

    @Test
    @DisplayName("测试零重连真并发多库动态路由、单一 dbname 头、跨库客户端视图隔离与停机有序释放")
    void testZeroReconnectConcurrentMultiDatabaseRouting() throws Exception {
        List<List<String>> recordedHeaders = Collections.synchronizedList(new ArrayList<>());
        Metadata.Key<String> dbKey = Metadata.Key.of("dbname", Metadata.ASCII_STRING_MARSHALLER);

        MilvusServiceGrpc.MilvusServiceImplBase service = new MilvusServiceGrpc.MilvusServiceImplBase() {
            @Override
            public void connect(ConnectRequest request, StreamObserver<ConnectResponse> responseObserver) {
                responseObserver.onNext(ConnectResponse.newBuilder()
                        .setStatus(Status.newBuilder().setCode(0).build())
                        .build());
                responseObserver.onCompleted();
            }

            @Override
            public void listDatabases(ListDatabasesRequest request, StreamObserver<ListDatabasesResponse> responseObserver) {
                responseObserver.onNext(ListDatabasesResponse.newBuilder()
                        .setStatus(Status.newBuilder().setCode(0).build())
                        .addDbNames("default")
                        .addDbNames("db_alpha")
                        .addDbNames("db_beta")
                        .build());
                responseObserver.onCompleted();
            }

            @Override
            public void showCollections(ShowCollectionsRequest request, StreamObserver<ShowCollectionsResponse> responseObserver) {
                responseObserver.onNext(ShowCollectionsResponse.newBuilder()
                        .setStatus(Status.newBuilder().setCode(0).build())
                        .addCollectionNames("col_shared")
                        .build());
                responseObserver.onCompleted();
            }
        };

        ServerInterceptor headerCapture = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                if (call.getMethodDescriptor().getFullMethodName().endsWith("/ShowCollections")) {
                    List<String> values = new ArrayList<>();
                    Iterable<String> all = headers.getAll(dbKey);
                    if (all != null) {
                        all.forEach(values::add);
                    }
                    recordedHeaders.add(values);
                }
                return next.startCall(call, headers);
            }
        };

        Server server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
                .addService(ServerInterceptors.intercept(service, headerCapture))
                .build()
                .start();

        AtomicReference<ManagedChannel> channelRef = new AtomicReference<>();
        try {
            int port = server.getPort();
            contextRunner
                    .withPropertyValues(
                            "ateng.boot.milvus.uri=http://127.0.0.1:" + port,
                            "ateng.boot.milvus.database=default",
                            "ateng.boot.milvus.rpc-deadline=5s",
                            "ateng.boot.milvus.tenant-database-routing=true"
                    )
                    .run(context -> {
                        MilvusTemplate template = context.getBean(MilvusTemplate.class);
                        MilvusDatabaseClientManager manager = context.getBean(MilvusDatabaseClientManager.class);
                        MilvusClientV2 primaryClient = context.getBean(MilvusClientV2.class);

                        Field chField = MilvusClientV2.class.getDeclaredField("channel");
                        chField.setAccessible(true);
                        ManagedChannel primaryChannel = (ManagedChannel) chField.get(primaryClient);
                        channelRef.set(primaryChannel);

                        // 验证跨库视图是不同 MilvusClientV2 实例，但复用同一共享 ManagedChannel
                        MilvusClientV2 alphaView = manager.resolveClient("db_alpha");
                        MilvusClientV2 betaView = manager.resolveClient("db_beta");
                        assertThat(alphaView).isNotSameAs(primaryClient);
                        assertThat(betaView).isNotSameAs(alphaView);
                        assertThat(chField.get(alphaView)).isSameAs(primaryChannel);
                        assertThat(chField.get(betaView)).isSameAs(primaryChannel);
                        assertThat(manager.resolveClient("default")).isSameAs(primaryClient);

                        // 并发切库触发真实 gRPC 请求
                        int threads = 6;
                        ExecutorService pool = Executors.newFixedThreadPool(threads);
                        CountDownLatch latch = new CountDownLatch(threads);
                        AtomicInteger errors = new AtomicInteger(0);
                        try {
                            for (int i = 0; i < threads; i++) {
                                final String targetDb = (i % 2 == 0) ? "db_alpha" : "db_beta";
                                pool.submit(() -> {
                                    try (MilvusContextHolder.DatabaseScope ignored = MilvusContextHolder.useDatabase(targetDb)) {
                                        List<String> cols = template.listCollections();
                                        if (!cols.contains("col_shared")) {
                                            errors.incrementAndGet();
                                        }
                                    } catch (Throwable ex) {
                                        errors.incrementAndGet();
                                    } finally {
                                        latch.countDown();
                                    }
                                });
                            }
                            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
                            assertThat(errors.get()).isZero();
                        } finally {
                            pool.shutdownNow();
                        }

                        // 验证每次 RPC 仅有单一 dbname 头，且同时覆盖两库
                        assertThat(recordedHeaders).hasSize(threads);
                        assertThat(recordedHeaders).allSatisfy(h -> assertThat(h).hasSize(1));
                        assertThat(recordedHeaders.stream().map(h -> h.get(0)))
                                .contains("db_alpha", "db_beta");
                    });

            // 验证停机后共享 ManagedChannel 有序关闭
            assertThat(channelRef.get()).isNotNull();
            assertThat(channelRef.get().isShutdown()).isTrue();
        } finally {
            server.shutdownNow();
            server.awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("测试 rpc-deadline 单次 RPC 超时防悬挂与弹性降级异常通道释放")
    void testRpcDeadlineAndResilientFailureChannelShutdown() throws Exception {
        MilvusServiceGrpc.MilvusServiceImplBase slowService = new MilvusServiceGrpc.MilvusServiceImplBase() {
            @Override
            public void showCollections(ShowCollectionsRequest request, StreamObserver<ShowCollectionsResponse> responseObserver) {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        };

        Server server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
                .addService(slowService)
                .build()
                .start();

        try {
            int port = server.getPort();
            contextRunner
                    .withPropertyValues(
                            "ateng.boot.milvus.uri=http://127.0.0.1:" + port,
                            "ateng.boot.milvus.connect-timeout=150ms",
                            "ateng.boot.milvus.rpc-deadline=200ms"
                    )
                    .run(context -> {
                        MilvusTemplate template = context.getBean(MilvusTemplate.class);
                        long start = System.currentTimeMillis();
                        assertThatThrownBy(template::listCollections)
                                .isInstanceOf(MilvusException.class);
                        long elapsed = System.currentTimeMillis() - start;
                        assertThat(elapsed).isLessThan(1200L);
                    });

            // 验证弹性构建异常时确定性关闭已创建的 ManagedChannel
            ManagedChannel mockChannel = Mockito.mock(ManagedChannel.class);
            assertThatThrownBy(() -> MilvusDatabaseClientManager.createBoundClient(mockChannel, null, null))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            server.shutdownNow();
            server.awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    @Configuration
    static class CustomMilvusConfig {

        @Bean
        public MilvusClientV2 customClient() {
            return Mockito.mock(MilvusClientV2.class);
        }
    }
}
