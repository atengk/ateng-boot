package io.github.atengk.boot.milvus;

import io.github.atengk.boot.milvus.annotation.MilvusCollection;
import io.github.atengk.boot.milvus.annotation.MilvusId;
import io.github.atengk.boot.milvus.annotation.MilvusVector;
import io.github.atengk.boot.milvus.properties.AtengMilvusProperties;
import io.github.atengk.boot.milvus.template.MilvusTemplate;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

        // listIndexes
        when(mockClient.listIndexes(any(ListIndexesReq.class))).thenReturn(List.of("vector_idx"));
        assertThat(milvusTemplate.listIndexes("my_col")).containsExactly("vector_idx");

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

        milvusTemplate.createCollectionAndIndex("param_col", "vector", 512,
                IndexParam.MetricType.IP, IndexParam.IndexType.FLAT);

        verify(mockClient).createCollection(any(CreateCollectionReq.class));
        verify(mockClient).createIndex(any(CreateIndexReq.class));
        verify(mockClient).loadCollection(any(LoadCollectionReq.class));
    }
}
