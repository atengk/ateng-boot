package io.github.atengk.boot.milvus;

import io.github.atengk.boot.milvus.annotation.MilvusCollection;
import io.github.atengk.boot.milvus.annotation.MilvusId;
import io.github.atengk.boot.milvus.annotation.MilvusVector;
import io.github.atengk.boot.milvus.client.MilvusDatabaseClientManager;
import io.github.atengk.boot.milvus.context.MilvusContextHolder;
import io.github.atengk.boot.milvus.exception.MilvusException;
import io.github.atengk.boot.milvus.exception.MilvusResultCode;
import io.github.atengk.boot.milvus.properties.AtengMilvusProperties;
import io.github.atengk.boot.milvus.template.MilvusTemplate;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.milvus.grpc.ErrorCode;
import io.milvus.grpc.FlushRequest;
import io.milvus.grpc.FlushResponse;
import io.milvus.grpc.MilvusServiceGrpc;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.GetLoadStateReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.collection.request.ReleaseCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.collection.response.ListCollectionsResp;
import io.milvus.v2.service.database.request.CreateDatabaseReq;
import io.milvus.v2.service.database.request.DropDatabaseReq;
import io.milvus.v2.service.database.response.ListDatabasesResp;
import io.milvus.v2.service.index.request.CreateIndexReq;
import io.milvus.v2.service.index.request.DescribeIndexReq;
import io.milvus.v2.service.index.request.DropIndexReq;
import io.milvus.v2.service.index.request.ListIndexesReq;
import io.milvus.v2.service.index.response.DescribeIndexResp;
import io.milvus.v2.service.partition.request.CreatePartitionReq;
import io.milvus.v2.service.partition.request.DropPartitionReq;
import io.milvus.v2.service.partition.request.HasPartitionReq;
import io.milvus.v2.service.partition.request.ListPartitionsReq;
import io.milvus.v2.service.partition.request.LoadPartitionsReq;
import io.milvus.v2.service.partition.request.ReleasePartitionsReq;
import lombok.Data;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MilvusTemplate 集合、索引、分区与数据库运维层 (DDL) 单元测试 (TestDouble 纯内存模式)
 *
 * @author Ateng
 * @since 2026-11-04
 */
class MilvusTemplateLifecycleTest {

    private MilvusClientV2 mockClient;
    private AtengMilvusProperties properties;
    private MilvusTemplate milvusTemplate;

    @Data
    @MilvusCollection(name = "lifecycle_demo")
    static class DemoVectorEntity {
        @MilvusId
        private String id;

        @MilvusVector(dimension = 128)
        private List<Float> vector;
    }

    @BeforeEach
    void setUp() {
        mockClient = Mockito.mock(MilvusClientV2.class);
        properties = new AtengMilvusProperties();
        milvusTemplate = new MilvusTemplate(mockClient, properties);
        MilvusContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        MilvusContextHolder.clear();
    }

    // =========================================================================
    // 集合生命周期测试
    // =========================================================================

    @Test
    @DisplayName("测试集合生命周期: hasCollection / createCollection / dropCollection / load / release")
    void testCollectionLifecycle() {
        // hasCollection
        when(mockClient.hasCollection(any(HasCollectionReq.class))).thenReturn(true);
        assertThat(milvusTemplate.hasCollection("my_collection")).isTrue();

        // createCollection (双参数)
        milvusTemplate.createCollection("my_collection", 1536);
        ArgumentCaptor<CreateCollectionReq> createCaptor = ArgumentCaptor.forClass(CreateCollectionReq.class);
        verify(mockClient).createCollection(createCaptor.capture());
        assertThat(createCaptor.getValue().getCollectionName()).isEqualTo("my_collection");
        assertThat(createCaptor.getValue().getDimension()).isEqualTo(1536);

        // createCollection (实体驱动)
        milvusTemplate.createCollection(DemoVectorEntity.class);
        verify(mockClient, times(2)).createCollection(any(CreateCollectionReq.class));

        // listCollections
        ListCollectionsResp listResp = Mockito.mock(ListCollectionsResp.class);
        when(listResp.getCollectionNames()).thenReturn(List.of("col1", "col2"));
        when(mockClient.listCollections()).thenReturn(listResp);
        assertThat(milvusTemplate.listCollections()).containsExactly("col1", "col2");

        // describeCollection
        DescribeCollectionResp descResp = Mockito.mock(DescribeCollectionResp.class);
        when(mockClient.describeCollection(any(DescribeCollectionReq.class))).thenReturn(descResp);
        assertThat(milvusTemplate.describeCollection("my_collection")).isSameAs(descResp);

        // loadCollection & releaseCollection
        milvusTemplate.loadCollection("my_collection");
        verify(mockClient).loadCollection(any(LoadCollectionReq.class));

        milvusTemplate.releaseCollection("my_collection");
        verify(mockClient).releaseCollection(any(ReleaseCollectionReq.class));

        // isCollectionLoaded
        when(mockClient.getLoadState(any(GetLoadStateReq.class))).thenReturn(true);
        assertThat(milvusTemplate.isCollectionLoaded("my_collection")).isTrue();

        // dropCollection
        milvusTemplate.dropCollection("my_collection");
        verify(mockClient).dropCollection(any(DropCollectionReq.class));
    }

    // =========================================================================
    // 索引生命周期测试
    // =========================================================================

    @Test
    @DisplayName("测试索引生命周期: createIndex / dropIndex / describeIndex / listIndexes")
    void testIndexLifecycle() {
        // createIndex (简易参数)
        milvusTemplate.createIndex("my_col", "vector", IndexParam.MetricType.COSINE, IndexParam.IndexType.HNSW);
        ArgumentCaptor<CreateIndexReq> indexCaptor = ArgumentCaptor.forClass(CreateIndexReq.class);
        verify(mockClient).createIndex(indexCaptor.capture());
        assertThat(indexCaptor.getValue().getCollectionName()).isEqualTo("my_col");
        assertThat(indexCaptor.getValue().getIndexParams()).hasSize(1);
        assertThat(indexCaptor.getValue().getIndexParams().get(0).getFieldName()).isEqualTo("vector");
        assertThat(indexCaptor.getValue().getIndexParams().get(0).getMetricType()).isEqualTo(IndexParam.MetricType.COSINE);
        assertThat(indexCaptor.getValue().getIndexParams().get(0).getIndexType()).isEqualTo(IndexParam.IndexType.HNSW);

        // describeIndex
        DescribeIndexResp descResp = Mockito.mock(DescribeIndexResp.class);
        when(mockClient.describeIndex(any(DescribeIndexReq.class))).thenReturn(descResp);
        assertThat(milvusTemplate.describeIndex("my_col", "vector")).isSameAs(descResp);

        // listIndexes (集合维度与字段维度)
        when(mockClient.listIndexes(any(ListIndexesReq.class))).thenReturn(List.of("vector_idx"));
        assertThat(milvusTemplate.listIndexes("my_col")).containsExactly("vector_idx");
        assertThat(milvusTemplate.listIndexes("my_col", "vector")).containsExactly("vector_idx");
        assertThat(milvusTemplate.hasIndex("my_col", "vector")).isTrue();

        // dropIndex
        milvusTemplate.dropIndex("my_col", "vector");
        verify(mockClient).dropIndex(any(DropIndexReq.class));
    }

    // =========================================================================
    // 分区生命周期测试
    // =========================================================================

    @Test
    @DisplayName("测试分区生命周期: hasPartition / createPartition / dropPartition / list / load / release")
    void testPartitionLifecycle() {
        // hasPartition
        when(mockClient.hasPartition(any(HasPartitionReq.class))).thenReturn(true);
        assertThat(milvusTemplate.hasPartition("my_col", "p_202611")).isTrue();

        // createPartition
        milvusTemplate.createPartition("my_col", "p_202611");
        verify(mockClient).createPartition(any(CreatePartitionReq.class));

        // listPartitions
        when(mockClient.listPartitions(any(ListPartitionsReq.class))).thenReturn(List.of("_default", "p_202611"));
        assertThat(milvusTemplate.listPartitions("my_col")).containsExactly("_default", "p_202611");

        // loadPartitions & releasePartitions
        milvusTemplate.loadPartitions("my_col", List.of("p_202611"));
        verify(mockClient).loadPartitions(any(LoadPartitionsReq.class));

        milvusTemplate.releasePartitions("my_col", List.of("p_202611"));
        verify(mockClient).releasePartitions(any(ReleasePartitionsReq.class));

        // dropPartition
        milvusTemplate.dropPartition("my_col", "p_202611");
        verify(mockClient).dropPartition(any(DropPartitionReq.class));
    }

    @Test
    @DisplayName("isPartitionsLoaded - 对称探测分区加载状态与空入参前置防卫")
    void testIsPartitionsLoaded() {
        // 1. 全部目标分区均已加载 -> true
        when(mockClient.getLoadState(any(GetLoadStateReq.class))).thenAnswer(inv -> {
            GetLoadStateReq req = inv.getArgument(0);
            assertThat(req.getCollectionName()).isEqualTo("my_col");
            return "p1".equals(req.getPartitionName()) || "p2".equals(req.getPartitionName());
        });
        assertThat(milvusTemplate.isPartitionsLoaded("my_col", List.of("p1", "p2"))).isTrue();

        // 2. 存在未加载分区 -> false
        assertThat(milvusTemplate.isPartitionsLoaded("my_col", List.of("p1", "p3"))).isFalse();

        // 3. 空入参前置防卫
        assertThatThrownBy(() -> milvusTemplate.isPartitionsLoaded(" ", List.of("p1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> milvusTemplate.isPartitionsLoaded("my_col", Collections.emptyList()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> milvusTemplate.isPartitionsLoaded("my_col", List.of(" ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("flush - 显式数据封存落盘、数据库上下文透传与异常因果链保留")
    void testFlushOperationsAndExceptionTranslation() throws Exception {
        // 1. 前置空校验
        assertThatThrownBy(() -> milvusTemplate.flush(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> milvusTemplate.flush(Collections.emptyList()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> milvusTemplate.flush(List.of("ok", " ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> milvusTemplate.flush((Class<?>) null))
                .isInstanceOf(IllegalArgumentException.class);

        // 2. 未挂载 gRPC 阻塞存根的测试桩 Fail-Fast 契约
        assertThatThrownBy(() -> milvusTemplate.flush("col_without_stub"))
                .isInstanceOf(MilvusException.class)
                .satisfies(ex -> assertThat(((MilvusException) ex).getResultCode())
                        .isEqualTo(MilvusResultCode.CLIENT_INIT_FAILED));

        // 3. 挂载真实 gRPC Flush 服务端验证请求内容、数据库透传与异常因果链
        List<FlushRequest> capturedRequests = new ArrayList<>();
        AtomicBoolean failNext = new AtomicBoolean(false);
        MilvusServiceGrpc.MilvusServiceImplBase grpcService = new MilvusServiceGrpc.MilvusServiceImplBase() {
            @Override
            public void flush(FlushRequest request, StreamObserver<FlushResponse> responseObserver) {
                capturedRequests.add(request);
                if (failNext.get()) {
                    responseObserver.onError(Status.INTERNAL.withDescription("disk flush failed").asRuntimeException());
                    return;
                }
                responseObserver.onNext(FlushResponse.newBuilder()
                        .setStatus(io.milvus.grpc.Status.newBuilder().setCode(0).setErrorCode(ErrorCode.Success).build())
                        .build());
                responseObserver.onCompleted();
            }
        };

        Server server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
                .addService(grpcService)
                .build()
                .start();
        ManagedChannel channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort())
                .usePlaintext()
                .build();
        try {
            MilvusServiceGrpc.MilvusServiceBlockingStub stub = MilvusServiceGrpc.newBlockingStub(channel);
            ConnectConfig cfg = ConnectConfig.builder().uri("http://127.0.0.1:" + server.getPort()).build();
            MilvusClientV2 boundClient = MilvusDatabaseClientManager.createBoundClient(channel, stub, cfg);
            MilvusTemplate grpcTemplate = new MilvusTemplate(boundClient, properties);

            try (MilvusContextHolder.DatabaseScope ignored = MilvusContextHolder.useDatabase("analytics_db")) {
                grpcTemplate.flush("single_col");
                grpcTemplate.flush(List.of("col_a", "col_b"));
                grpcTemplate.flush(DemoVectorEntity.class);
            }

            assertThat(capturedRequests).hasSize(3);
            assertThat(capturedRequests.get(0).getDbName()).isEqualTo("analytics_db");
            assertThat(capturedRequests.get(0).getCollectionNamesList()).containsExactly("single_col");
            assertThat(capturedRequests.get(1).getDbName()).isEqualTo("analytics_db");
            assertThat(capturedRequests.get(1).getCollectionNamesList()).containsExactly("col_a", "col_b");
            assertThat(capturedRequests.get(2).getCollectionNamesList()).containsExactly("lifecycle_demo");

            // 验证底层 gRPC 异常转译为 MilvusException 并保留 cause
            failNext.set(true);
            assertThatThrownBy(() -> grpcTemplate.flush("broken_col"))
                    .isInstanceOf(MilvusException.class)
                    .hasMessageContaining("disk flush failed")
                    .hasCauseInstanceOf(io.grpc.StatusRuntimeException.class);
        } finally {
            MilvusDatabaseClientManager.shutdownChannelQuietly(channel);
            server.shutdownNow();
            server.awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    // =========================================================================
    // 逻辑数据库运维测试
    // =========================================================================

    @Test
    @DisplayName("测试逻辑数据库运维: listDatabases / createDatabase / dropDatabase")
    void testDatabaseLifecycle() {
        ListDatabasesResp dbResp = Mockito.mock(ListDatabasesResp.class);
        when(dbResp.getDatabaseNames()).thenReturn(List.of("default", "knowledge_base"));
        when(mockClient.listDatabases()).thenReturn(dbResp);
        assertThat(milvusTemplate.listDatabases()).containsExactly("default", "knowledge_base");

        milvusTemplate.createDatabase("ai_db");
        verify(mockClient).createDatabase(any(CreateDatabaseReq.class));

        milvusTemplate.dropDatabase("ai_db");
        verify(mockClient).dropDatabase(any(DropDatabaseReq.class));
    }

    // =========================================================================
    // 一键建表建索引预加载测试
    // =========================================================================

    @Test
    @DisplayName("测试一键建表建索引预加载 createCollectionAndIndex (实体驱动)")
    void testCreateCollectionAndIndexEntityDriven() {
        // 模拟集合尚未创建、索引尚未建立、尚未加载
        when(mockClient.hasCollection(any(HasCollectionReq.class))).thenReturn(false);
        when(mockClient.listIndexes(any(ListIndexesReq.class))).thenReturn(Collections.emptyList());
        when(mockClient.getLoadState(any(GetLoadStateReq.class))).thenReturn(false);

        milvusTemplate.createCollectionAndIndex(DemoVectorEntity.class);

        // 验证原子步骤全部依次被调用
        verify(mockClient).createCollection(any(CreateCollectionReq.class));
        verify(mockClient).createIndex(any(CreateIndexReq.class));
        verify(mockClient).loadCollection(any(LoadCollectionReq.class));
    }

    @Test
    @DisplayName("测试一键建表建索引预加载 createCollectionAndIndex (显式参数驱动)")
    void testCreateCollectionAndIndexExplicitDriven() {
        when(mockClient.hasCollection(any(HasCollectionReq.class))).thenReturn(false);
        when(mockClient.listIndexes(any(ListIndexesReq.class))).thenReturn(Collections.emptyList());
        when(mockClient.getLoadState(any(GetLoadStateReq.class))).thenReturn(false);

        milvusTemplate.createCollectionAndIndex("param_col", "custom_vector", 512,
                IndexParam.MetricType.IP, IndexParam.IndexType.FLAT);

        ArgumentCaptor<CreateCollectionReq> createCaptor = ArgumentCaptor.forClass(CreateCollectionReq.class);
        verify(mockClient).createCollection(createCaptor.capture());
        assertThat(createCaptor.getValue().getCollectionName()).isEqualTo("param_col");
        assertThat(createCaptor.getValue().getDimension()).isEqualTo(512);
        assertThat(createCaptor.getValue().getVectorFieldName()).isEqualTo("custom_vector");
        assertThat(createCaptor.getValue().getMetricType()).isEqualTo("IP");

        ArgumentCaptor<CreateIndexReq> indexCaptor = ArgumentCaptor.forClass(CreateIndexReq.class);
        verify(mockClient).createIndex(indexCaptor.capture());
        assertThat(indexCaptor.getValue().getIndexParams().get(0).getFieldName()).isEqualTo("custom_vector");
        assertThat(indexCaptor.getValue().getIndexParams().get(0).getMetricType()).isEqualTo(IndexParam.MetricType.IP);

        verify(mockClient).loadCollection(any(LoadCollectionReq.class));
    }

    @Test
    @DisplayName("测试一键建表当向量索引已存在且已加载时幂等跳过")
    void testCreateCollectionAndIndexIdempotent() {
        when(mockClient.hasCollection(any(HasCollectionReq.class))).thenReturn(true);
        when(mockClient.listIndexes(any(ListIndexesReq.class))).thenReturn(List.of("vector_idx"));
        when(mockClient.getLoadState(any(GetLoadStateReq.class))).thenReturn(true);

        milvusTemplate.createCollectionAndIndex(DemoVectorEntity.class);

        verify(mockClient, Mockito.never()).createCollection(any(CreateCollectionReq.class));
        verify(mockClient, Mockito.never()).createIndex(any(CreateIndexReq.class));
        verify(mockClient, Mockito.never()).loadCollection(any(LoadCollectionReq.class));
    }
}
