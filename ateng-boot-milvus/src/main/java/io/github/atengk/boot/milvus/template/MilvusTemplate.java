package io.github.atengk.boot.milvus.template;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.util.BatchSliceUtil;
import io.github.atengk.boot.core.util.RetryUtil;
import io.github.atengk.boot.milvus.context.MilvusContextHolder;
import io.github.atengk.boot.milvus.exception.MilvusException;
import io.github.atengk.boot.milvus.exception.MilvusExceptionTranslator;
import io.github.atengk.boot.milvus.model.AnnSearch;
import io.github.atengk.boot.milvus.model.MilvusEntityConverter;
import io.github.atengk.boot.milvus.model.MilvusEntityMetadata;
import io.github.atengk.boot.milvus.model.MilvusHit;
import io.github.atengk.boot.milvus.model.MilvusHybridSearchRequest;
import io.github.atengk.boot.milvus.model.MilvusSearchRequest;
import io.github.atengk.boot.milvus.model.ReRanker;
import io.github.atengk.boot.milvus.properties.AtengMilvusProperties;
import io.github.atengk.boot.milvus.properties.MilvusConsistencyLevel;
import io.github.atengk.boot.milvus.tenant.MilvusTenantHelper;
import io.github.atengk.boot.milvus.util.MilvusExprHelper;
import io.milvus.orm.iterator.QueryIterator;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.GetCollectionStatsReq;
import io.milvus.v2.service.collection.request.GetLoadStateReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.collection.request.ReleaseCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.collection.response.GetCollectionStatsResp;
import io.milvus.v2.service.database.request.CreateDatabaseReq;
import io.milvus.v2.service.database.request.DropDatabaseReq;
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
import io.milvus.v2.service.utility.request.AlterAliasReq;
import io.milvus.v2.service.utility.request.CreateAliasReq;
import io.milvus.v2.service.utility.request.DescribeAliasReq;
import io.milvus.v2.service.utility.request.DropAliasReq;
import io.milvus.v2.service.utility.request.ListAliasesReq;
import io.milvus.v2.service.utility.response.DescribeAliasResp;
import io.milvus.v2.service.utility.response.ListAliasResp;
import io.milvus.v2.service.vector.request.AnnSearchReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.GetReq;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.QueryIteratorReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.request.ranker.BaseRanker;
import io.milvus.v2.service.vector.response.GetResp;
import io.milvus.v2.service.vector.response.InsertResp;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.SearchResp;
import io.milvus.v2.service.vector.response.UpsertResp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Milvus 向量数据库核心操作门面 (三层立体分工与双核穿透)
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Slf4j
public class MilvusTemplate {

    public static final int DEFAULT_BATCH_SIZE = 1000;
    private static final RetryUtil.RetryConfig DEFAULT_RETRY_CONFIG = RetryUtil.RetryConfig.builder()
            .maxAttempts(3)
            .delay(Duration.ofMillis(200))
            .multiplier(2.0)
            .maxDelay(Duration.ofSeconds(2))
            .build();

    private final MilvusClientV2 client;
    private final AtengMilvusProperties properties;

    public MilvusTemplate(MilvusClientV2 client, AtengMilvusProperties properties) {
        Assert.notNull(client, "MilvusClientV2 must not be null");
        Assert.notNull(properties, "AtengMilvusProperties must not be null");
        this.client = client;
        this.properties = properties;
    }

    /**
     * 获取底层原生 MilvusClientV2 客户端 (双核穿透逃逸舱)
     *
     * @return 官方原生 MilvusClientV2 实例
     */
    public MilvusClientV2 getClient() {
        return client;
    }

    /**
     * 获取当前生效的配置属性
     *
     * @return AtengMilvusProperties 配置对象
     */
    public AtengMilvusProperties getProperties() {
        return properties;
    }

    /**
     * 无损测试 Milvus 服务端连通性
     *
     * @return 是否成功连通
     */
    public boolean testConnection() {
        try {
            return client.clientIsReady();
        } catch (Exception e) {
            log.warn("Milvus 健康探针探测异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 获取 Milvus 服务端版本信息
     *
     * @return 服务端版本字符串 (如 "v2.4.4")
     */
    public String getServerVersion() {
        return execute("获取服务端版本", MilvusClientV2::getServerVersion);
    }

    // =========================================================================
    // 1. 集合生命周期运维 (Collection DDL Operations)
    // =========================================================================

    /**
     * 判断集合是否存在
     *
     * @param collectionName 集合名称
     * @return 是否存在
     */
    public boolean hasCollection(String collectionName) {
        Assert.hasText(collectionName, "collectionName must not be empty");
        return execute("判断集合是否存在", c -> c.hasCollection(HasCollectionReq.builder()
                .collectionName(collectionName)
                .build()));
    }

    /**
     * 根据自定义请求创建集合
     *
     * @param request 创建集合参数
     */
    public void createCollection(CreateCollectionReq request) {
        Assert.notNull(request, "CreateCollectionReq must not be null");
        execute("创建集合", c -> {
            c.createCollection(request);
            return null;
        });
    }

    /**
     * 极简创建集合 (默认生成 id 主键与 vector 向量列)
     *
     * @param collectionName 集合名称
     * @param dimension      向量维度
     */
    public void createCollection(String collectionName, int dimension) {
        createCollection(collectionName, dimension, null);
    }

    /**
     * 极简创建集合 (指定向量字段名，默认生成 id 主键)
     *
     * @param collectionName  集合名称
     * @param dimension       向量维度
     * @param vectorFieldName 向量字段名称 (可选，为空时 SDK 默认为 "vector")
     */
    public void createCollection(String collectionName, int dimension, String vectorFieldName) {
        Assert.hasText(collectionName, "collectionName must not be empty");
        Assert.isTrue(dimension > 0, "dimension must be positive");
        CreateCollectionReq.CreateCollectionReqBuilder<?, ?> builder = CreateCollectionReq.builder()
                .collectionName(collectionName)
                .dimension(dimension);
        if (StringUtils.hasText(vectorFieldName)) {
            builder.vectorFieldName(vectorFieldName.trim());
        }
        createCollection(builder.build());
    }

    /**
     * 根据实体类注解自动推导并创建集合
     *
     * @param entityClass 实体类
     */
    public void createCollection(Class<?> entityClass) {
        Assert.notNull(entityClass, "entityClass must not be null");
        MilvusEntityMetadata metadata = MilvusEntityMetadata.of(entityClass);
        createCollection(metadata.toCreateCollectionReq());
    }

    /**
     * 删除指定集合
     *
     * @param collectionName 集合名称
     */
    public void dropCollection(String collectionName) {
        Assert.hasText(collectionName, "collectionName must not be empty");
        execute("删除集合", c -> {
            c.dropCollection(DropCollectionReq.builder()
                    .collectionName(collectionName)
                    .build());
            return null;
        });
    }

    /**
     * 获取当前所有集合名称列表
     *
     * @return 集合名称列表
     */
    public List<String> listCollections() {
        return execute("列出集合", c -> {
            var resp = c.listCollections();
            return resp != null && resp.getCollectionNames() != null ? resp.getCollectionNames() : Collections.emptyList();
        });
    }

    /**
     * 查询集合详细结构定义
     *
     * @param collectionName 集合名称
     * @return 集合结构详情
     */
    public DescribeCollectionResp describeCollection(String collectionName) {
        Assert.hasText(collectionName, "collectionName must not be empty");
        return execute("查看集合结构", c -> c.describeCollection(DescribeCollectionReq.builder()
                .collectionName(collectionName)
                .build()));
    }

    /**
     * 获取集合统计信息 (包含集合实体总行数)
     *
     * @param collectionName 集合名称
     * @return 集合统计详情响应
     */
    public GetCollectionStatsResp getCollectionStats(String collectionName) {
        Assert.hasText(collectionName, "collectionName must not be empty");
        return execute("获取集合统计信息", c -> c.getCollectionStats(GetCollectionStatsReq.builder()
                .collectionName(collectionName)
                .build()));
    }

    /**
     * 将集合全量加载至内存以备检索
     *
     * @param collectionName 集合名称
     */
    public void loadCollection(String collectionName) {
        Assert.hasText(collectionName, "collectionName must not be empty");
        execute("加载集合到内存", c -> {
            c.loadCollection(LoadCollectionReq.builder()
                    .collectionName(collectionName)
                    .build());
            return null;
        });
    }

    /**
     * 从内存中释放集合 (释放物理内存占用)
     *
     * @param collectionName 集合名称
     */
    public void releaseCollection(String collectionName) {
        Assert.hasText(collectionName, "collectionName must not be empty");
        execute("释放内存集合", c -> {
            c.releaseCollection(ReleaseCollectionReq.builder()
                    .collectionName(collectionName)
                    .build());
            return null;
        });
    }

    /**
     * 探测集合是否已被加载至内存
     *
     * @param collectionName 集合名称
     * @return 是否已就绪加载
     */
    public boolean isCollectionLoaded(String collectionName) {
        Assert.hasText(collectionName, "collectionName must not be empty");
        return execute("探测集合加载状态", c -> {
            Boolean loaded = c.getLoadState(GetLoadStateReq.builder()
                    .collectionName(collectionName)
                    .build());
            return Boolean.TRUE.equals(loaded);
        });
    }

    // =========================================================================
    // 2. 向量索引生命周期运维 (Index DDL Operations)
    // =========================================================================

    /**
     * 根据自定义请求创建索引
     *
     * @param request 创建索引参数
     */
    public void createIndex(CreateIndexReq request) {
        Assert.notNull(request, "CreateIndexReq must not be null");
        execute("创建索引", c -> {
            c.createIndex(request);
            return null;
        });
    }

    /**
     * 为指定向量列极简创建索引
     *
     * @param collectionName 集合名称
     * @param fieldName      向量字段名
     * @param metricType     距离度量类型
     * @param indexType      索引算法类型
     */
    public void createIndex(String collectionName, String fieldName,
                            IndexParam.MetricType metricType, IndexParam.IndexType indexType) {
        Assert.hasText(collectionName, "collectionName must not be empty");
        Assert.hasText(fieldName, "fieldName must not be empty");
        Assert.notNull(metricType, "metricType must not be null");
        Assert.notNull(indexType, "indexType must not be null");

        IndexParam indexParam = IndexParam.builder()
                .fieldName(fieldName)
                .metricType(metricType)
                .indexType(indexType)
                .build();

        CreateIndexReq req = CreateIndexReq.builder()
                .collectionName(collectionName)
                .indexParams(List.of(indexParam))
                .build();
        createIndex(req);
    }

    /**
     * 为指定标量字段创建标量索引 (支持 INVERTED 倒排索引、TRIE 前缀树、STL_SORT 排序等)
     *
     * @param collectionName 集合名称
     * @param fieldName      标量字段名
     * @param indexName      自定义索引名称 (可为空)
     * @param indexType      标量索引算法类型
     */
    public void createIndex(String collectionName, String fieldName, String indexName, IndexParam.IndexType indexType) {
        Assert.hasText(collectionName, "collectionName must not be empty");
        Assert.hasText(fieldName, "fieldName must not be empty");
        Assert.notNull(indexType, "indexType must not be null");

        IndexParam.IndexParamBuilder<?, ?> paramBuilder = IndexParam.builder()
                .fieldName(fieldName)
                .indexType(indexType);
        if (StringUtils.hasText(indexName)) {
            paramBuilder.indexName(indexName.trim());
        }

        CreateIndexReq req = CreateIndexReq.builder()
                .collectionName(collectionName)
                .indexParams(Collections.singletonList(paramBuilder.build()))
                .build();
        createIndex(req);
    }

    /**
     * 删除指定字段上的索引
     *
     * @param collectionName 集合名称
     * @param fieldName      字段名称
     */
    public void dropIndex(String collectionName, String fieldName) {
        Assert.hasText(collectionName, "collectionName must not be empty");
        Assert.hasText(fieldName, "fieldName must not be empty");
        execute("删除索引", c -> {
            c.dropIndex(DropIndexReq.builder()
                    .collectionName(collectionName)
                    .fieldName(fieldName)
                    .build());
            return null;
        });
    }

    /**
     * 查询指定字段的索引元数据详情
     *
     * @param collectionName 集合名称
     * @param fieldName      字段名称
     * @return 索引结构信息
     */
    public DescribeIndexResp describeIndex(String collectionName, String fieldName) {
        Assert.hasText(collectionName, "collectionName must not be empty");
        Assert.hasText(fieldName, "fieldName must not be empty");
        return execute("查看索引信息", c -> c.describeIndex(DescribeIndexReq.builder()
                .collectionName(collectionName)
                .fieldName(fieldName)
                .build()));
    }

    /**
     * 列出集合上已建立的所有索引名称
     *
     * @param collectionName 集合名称
     * @return 索引名称列表
     */
    public List<String> listIndexes(String collectionName) {
        Assert.hasText(collectionName, "collectionName must not be empty");
        return execute("列出索引", c -> {
            var indexes = c.listIndexes(ListIndexesReq.builder()
                    .collectionName(collectionName)
                    .build());
            return indexes != null ? indexes : Collections.emptyList();
        });
    }

    /**
     * 列出集合指定字段上已建立的所有索引名称
     *
     * @param collectionName 集合名称
     * @param fieldName      字段名称
     * @return 索引名称列表
     */
    public List<String> listIndexes(String collectionName, String fieldName) {
        Assert.hasText(collectionName, "collectionName must not be empty");
        Assert.hasText(fieldName, "fieldName must not be empty");
        return execute("列出字段索引", c -> {
            var indexes = c.listIndexes(ListIndexesReq.builder()
                    .collectionName(collectionName)
                    .fieldName(fieldName)
                    .build());
            return indexes != null ? indexes : Collections.emptyList();
        });
    }

    /**
     * 探测集合指定字段上是否已建立索引
     *
     * @param collectionName 集合名称
     * @param fieldName      字段名称
     * @return 是否已建索引
     */
    public boolean hasIndex(String collectionName, String fieldName) {
        List<String> indexes = listIndexes(collectionName, fieldName);
        return !indexes.isEmpty();
    }

    // =========================================================================
    // 3. 物理分区生命周期运维 (Partition DDL Operations)
    // =========================================================================

    /**
     * 判断集合内指定物理分区是否存在
     *
     * @param collectionName 集合名称
     * @param partitionName  分区名称
     * @return 是否存在
     */
    public boolean hasPartition(String collectionName, String partitionName) {
        Assert.hasText(collectionName, "collectionName must not be empty");
        Assert.hasText(partitionName, "partitionName must not be empty");
        return execute("判断分区是否存在", c -> c.hasPartition(HasPartitionReq.builder()
                .collectionName(collectionName)
                .partitionName(partitionName)
                .build()));
    }

    /**
     * 创建物理分区
     *
     * @param collectionName 集合名称
     * @param partitionName  分区名称
     */
    public void createPartition(String collectionName, String partitionName) {
        Assert.hasText(collectionName, "collectionName must not be empty");
        Assert.hasText(partitionName, "partitionName must not be empty");
        execute("创建分区", c -> {
            c.createPartition(CreatePartitionReq.builder()
                    .collectionName(collectionName)
                    .partitionName(partitionName)
                    .build());
            return null;
        });
    }

    /**
     * 删除物理分区
     *
     * @param collectionName 集合名称
     * @param partitionName  分区名称
     */
    public void dropPartition(String collectionName, String partitionName) {
        Assert.hasText(collectionName, "collectionName must not be empty");
        Assert.hasText(partitionName, "partitionName must not be empty");
        execute("删除分区", c -> {
            c.dropPartition(DropPartitionReq.builder()
                    .collectionName(collectionName)
                    .partitionName(partitionName)
                    .build());
            return null;
        });
    }

    /**
     * 列出集合内所有物理分区名称
     *
     * @param collectionName 集合名称
     * @return 分区名称列表
     */
    public List<String> listPartitions(String collectionName) {
        Assert.hasText(collectionName, "collectionName must not be empty");
        return execute("列出分区", c -> {
            var parts = c.listPartitions(ListPartitionsReq.builder()
                    .collectionName(collectionName)
                    .build());
            return parts != null ? parts : Collections.emptyList();
        });
    }

    /**
     * 加载指定物理分区至内存
     *
     * @param collectionName 集合名称
     * @param partitionNames 分区名称列表
     */
    public void loadPartitions(String collectionName, List<String> partitionNames) {
        Assert.hasText(collectionName, "collectionName must not be empty");
        Assert.notEmpty(partitionNames, "partitionNames must not be empty");
        execute("加载分区到内存", c -> {
            c.loadPartitions(LoadPartitionsReq.builder()
                    .collectionName(collectionName)
                    .partitionNames(partitionNames)
                    .build());
            return null;
        });
    }

    /**
     * 从内存中释放指定物理分区
     *
     * @param collectionName 集合名称
     * @param partitionNames 分区名称列表
     */
    public void releasePartitions(String collectionName, List<String> partitionNames) {
        Assert.hasText(collectionName, "collectionName must not be empty");
        Assert.notEmpty(partitionNames, "partitionNames must not be empty");
        execute("从内存释放分区", c -> {
            c.releasePartitions(ReleasePartitionsReq.builder()
                    .collectionName(collectionName)
                    .partitionNames(partitionNames)
                    .build());
            return null;
        });
    }

    // =========================================================================
    // 4. 一键建表、建索引与预加载 (One-Stop Setup)
    // =========================================================================

    /**
     * 一站式快捷建表、建立向量索引并自动加载到内存 (实体模型驱动)
     *
     * @param entityClass 标记了 @MilvusCollection 的实体类
     */
    public void createCollectionAndIndex(Class<?> entityClass) {
        Assert.notNull(entityClass, "entityClass must not be null");
        MilvusEntityMetadata metadata = MilvusEntityMetadata.of(entityClass);
        String collectionName = metadata.getCollectionName();

        // 1. 若集合不存在则创建
        if (!hasCollection(collectionName)) {
            log.info("一键建表: 集合 [{}] 不存在，正在根据实体 [{}] 创建 Schema", collectionName, entityClass.getSimpleName());
            createCollection(metadata.toCreateCollectionReq());
        }

        // 2. 为所有声明的向量列原子化创建索引 (稠密 FloatVector / 稀疏 SparseFloatVector)
        List<MilvusEntityMetadata.FieldMetadata> vectorFields = metadata.getVectorFields() != null && !metadata.getVectorFields().isEmpty()
                ? metadata.getVectorFields()
                : Collections.singletonList(metadata.getVectorField());

        for (MilvusEntityMetadata.FieldMetadata vf : vectorFields) {
            if (!hasIndex(collectionName, vf.getFieldName())) {
                IndexParam.MetricType metricType = vf.getMetricType() != null
                        ? vf.getMetricType()
                        : (vf.getDataType() == io.milvus.v2.common.DataType.SparseFloatVector ? IndexParam.MetricType.IP : IndexParam.MetricType.COSINE);
                IndexParam.IndexType indexType = vf.getIndexType() != null
                        ? vf.getIndexType()
                        : (vf.getDataType() == io.milvus.v2.common.DataType.SparseFloatVector ? IndexParam.IndexType.SPARSE_INVERTED_INDEX : IndexParam.IndexType.AUTOINDEX);

                log.info("一键建表: 集合 [{}] 向量列 [{}] 正在创建索引 [type={}, metric={}]",
                        collectionName, vf.getFieldName(), indexType, metricType);
                createIndex(collectionName, vf.getFieldName(), metricType, indexType);
            }
        }

        // 3. 为配置了标量索引的字段创建标量索引 (倒排索引/前缀树/排序等)
        if (metadata.getScalarFields() != null) {
            for (MilvusEntityMetadata.FieldMetadata scalarField : metadata.getScalarFields()) {
                if (scalarField.getIndexType() != null && scalarField.getIndexType() != IndexParam.IndexType.None) {
                    if (!hasIndex(collectionName, scalarField.getFieldName())) {
                        String idxName = StringUtils.hasText(scalarField.getIndexName())
                                ? scalarField.getIndexName().trim()
                                : "idx_" + scalarField.getFieldName();
                        log.info("一键建表: 集合 [{}] 标量列 [{}] 正在创建标量索引 [name={}, type={}]",
                                collectionName, scalarField.getFieldName(), idxName, scalarField.getIndexType());
                        createIndex(collectionName, scalarField.getFieldName(), idxName, scalarField.getIndexType());
                    }
                }
            }
        }

        // 4. 若尚未加载至内存则预加载
        if (!isCollectionLoaded(collectionName)) {
            log.info("一键建表: 正在将集合 [{}] 加载至内存", collectionName);
            loadCollection(collectionName);
        }
    }

    /**
     * 一站式快捷建表、建立向量索引并自动加载到内存 (显式参数驱动)
     *
     * @param collectionName  集合名称
     * @param vectorFieldName 向量字段名称 (如 "vector")
     * @param dimension       向量维度 (如 1536)
     * @param metricType      距离度量类型 (如 COSINE)
     * @param indexType       索引算法类型 (如 AUTOINDEX / HNSW)
     */
    public void createCollectionAndIndex(String collectionName, String vectorFieldName,
                                         int dimension, IndexParam.MetricType metricType,
                                         IndexParam.IndexType indexType) {
        Assert.hasText(collectionName, "collectionName must not be empty");
        Assert.hasText(vectorFieldName, "vectorFieldName must not be empty");
        Assert.isTrue(dimension > 0, "dimension must be positive");
        Assert.notNull(metricType, "metricType must not be null");
        Assert.notNull(indexType, "indexType must not be null");

        // 1. 若不存在则创建集合 (显式携带 vectorFieldName 与 metricType)
        if (!hasCollection(collectionName)) {
            log.info("一键建表: 集合 [{}] 不存在，正在创建 (dim={}, vectorField={})", collectionName, dimension, vectorFieldName);
            CreateCollectionReq req = CreateCollectionReq.builder()
                    .collectionName(collectionName)
                    .dimension(dimension)
                    .vectorFieldName(vectorFieldName)
                    .metricType(metricType.name())
                    .build();
            createCollection(req);
        }

        // 2. 创建向量索引与内存预加载
        ensureIndexAndLoaded(collectionName, vectorFieldName, metricType, indexType);
    }

    private void ensureIndexAndLoaded(String collectionName, String vectorFieldName,
                                      IndexParam.MetricType metricType, IndexParam.IndexType indexType) {
        // 若指定向量列尚未建立索引则创建
        if (!hasIndex(collectionName, vectorFieldName)) {
            log.info("一键建表: 集合 [{}] 向量列 [{}] 正在创建索引 [type={}, metric={}]",
                    collectionName, vectorFieldName, indexType, metricType);
            createIndex(collectionName, vectorFieldName, metricType, indexType);
        }

        // 若尚未加载至内存则预加载
        if (!isCollectionLoaded(collectionName)) {
            log.info("一键建表: 正在将集合 [{}] 加载至内存", collectionName);
            loadCollection(collectionName);
        }
    }

    // =========================================================================
    // 5. 逻辑数据库生命周期运维 (Database DDL Operations)
    // =========================================================================

    /**
     * 获取所有逻辑数据库名称列表
     *
     * @return 数据库名称列表
     */
    public List<String> listDatabases() {
        return execute("列出逻辑数据库", c -> {
            var resp = c.listDatabases();
            return resp != null && resp.getDatabaseNames() != null ? resp.getDatabaseNames() : Collections.emptyList();
        });
    }

    /**
     * 创建逻辑数据库
     *
     * @param databaseName 数据库名称
     */
    public void createDatabase(String databaseName) {
        Assert.hasText(databaseName, "databaseName must not be empty");
        execute("创建数据库", c -> {
            c.createDatabase(CreateDatabaseReq.builder()
                    .databaseName(databaseName)
                    .build());
            return null;
        });
    }

    /**
     * 删除逻辑数据库
     *
     * @param databaseName 数据库名称
     */
    public void dropDatabase(String databaseName) {
        Assert.hasText(databaseName, "databaseName must not be empty");
        execute("删除数据库", c -> {
            c.dropDatabase(DropDatabaseReq.builder()
                    .databaseName(databaseName)
                    .build());
            return null;
        });
    }

    // =========================================================================
    // 5.1 集合别名生命周期运维与蓝绿无感发布 (Collection Alias Operations)
    // =========================================================================

    /**
     * 为集合创建逻辑别名
     *
     * @param collectionName 真实集合名称
     * @param alias          逻辑别名
     */
    public void createAlias(String collectionName, String alias) {
        Assert.hasText(collectionName, "collectionName must not be empty");
        Assert.hasText(alias, "alias must not be empty");
        createAlias(CreateAliasReq.builder()
                .collectionName(collectionName.trim())
                .alias(alias.trim())
                .build());
    }

    /**
     * 根据自定义请求创建集合逻辑别名
     *
     * @param request 创建别名参数
     */
    public void createAlias(CreateAliasReq request) {
        Assert.notNull(request, "CreateAliasReq must not be null");
        Assert.hasText(request.getCollectionName(), "collectionName must not be empty");
        Assert.hasText(request.getAlias(), "alias must not be empty");
        execute("创建集合别名", c -> {
            c.createAlias(request);
            return null;
        });
    }

    /**
     * 变更逻辑别名指向的集合 (蓝绿发布无感切流)，默认强制前置探测目标集合是否已加载至内存
     *
     * @param collectionName 新目标集合名称
     * @param alias          逻辑别名
     */
    public void alterAlias(String collectionName, String alias) {
        alterAlias(collectionName, alias, true);
    }

    /**
     * 变更逻辑别名指向的集合 (蓝绿发布无感切流)
     *
     * @param collectionName 新目标集合名称
     * @param alias          逻辑别名
     * @param checkLoaded    是否强制要求目标集合已预加载至内存 (防御性切流保护，推荐为 true)
     */
    public void alterAlias(String collectionName, String alias, boolean checkLoaded) {
        Assert.hasText(collectionName, "collectionName must not be empty");
        Assert.hasText(alias, "alias must not be empty");
        alterAlias(AlterAliasReq.builder()
                .collectionName(collectionName.trim())
                .alias(alias.trim())
                .build(), checkLoaded);
    }

    /**
     * 根据自定义请求变更集合别名指向
     *
     * @param request     变更别名参数
     * @param checkLoaded 是否强制校验目标集合加载状态
     */
    public void alterAlias(AlterAliasReq request, boolean checkLoaded) {
        Assert.notNull(request, "AlterAliasReq must not be null");
        Assert.hasText(request.getCollectionName(), "collectionName must not be empty");
        Assert.hasText(request.getAlias(), "alias must not be empty");

        String targetCollection = request.getCollectionName().trim();
        String alias = request.getAlias().trim();

        // 防御性切流保护：校验目标新集合是否已通过 isCollectionLoaded 加载至内存
        if (checkLoaded && !isCollectionLoaded(targetCollection)) {
            throw new MilvusException("无法将别名 [" + alias + "] 切换至目标集合 ["
                    + targetCollection + "]：目标集合尚未加载至内存 (Not Loaded)，请先执行 loadCollection 以免引发在线检索异常");
        }

        execute("变更集合别名", c -> {
            c.alterAlias(request);
            return null;
        });
    }

    /**
     * 删除指定的集合别名
     *
     * @param alias 逻辑别名
     */
    public void dropAlias(String alias) {
        Assert.hasText(alias, "alias must not be empty");
        dropAlias(DropAliasReq.builder()
                .alias(alias.trim())
                .build());
    }

    /**
     * 根据自定义请求删除集合逻辑别名
     *
     * @param request 删除别名参数
     */
    public void dropAlias(DropAliasReq request) {
        Assert.notNull(request, "DropAliasReq must not be null");
        Assert.hasText(request.getAlias(), "alias must not be empty");
        execute("删除集合别名", c -> {
            c.dropAlias(request);
            return null;
        });
    }

    /**
     * 获取指定集合绑定的所有别名列表
     *
     * @param collectionName 集合名称
     * @return 别名列表 (保底返回空列表，绝不返回 null)
     */
    public List<String> listAliases(String collectionName) {
        Assert.hasText(collectionName, "collectionName must not be empty");
        return listAliases(ListAliasesReq.builder()
                .collectionName(collectionName.trim())
                .build());
    }

    /**
     * 根据自定义请求查询集合别名列表
     *
     * @param request 查询别名参数
     * @return 别名列表 (保底返回空列表，绝不返回 null)
     */
    public List<String> listAliases(ListAliasesReq request) {
        Assert.notNull(request, "ListAliasesReq must not be null");
        Assert.hasText(request.getCollectionName(), "collectionName must not be empty");
        return execute("查询集合别名列表", c -> {
            ListAliasResp resp = c.listAliases(request);
            return resp != null && resp.getAlias() != null ? resp.getAlias() : Collections.emptyList();
        });
    }

    /**
     * 查询指定别名的详细元数据信息 (绑定的目标集合等)
     *
     * @param alias 逻辑别名
     * @return 别名元数据详情
     */
    public DescribeAliasResp describeAlias(String alias) {
        Assert.hasText(alias, "alias must not be empty");
        return describeAlias(DescribeAliasReq.builder()
                .alias(alias.trim())
                .build());
    }

    /**
     * 根据自定义请求查询别名元数据详情
     *
     * @param request 查询别名请求
     * @return 别名元数据详情
     */
    public DescribeAliasResp describeAlias(DescribeAliasReq request) {
        Assert.notNull(request, "DescribeAliasReq must not be null");
        Assert.hasText(request.getAlias(), "alias must not be empty");
        return execute("查询集合别名详情", c -> c.describeAlias(request));
    }

    /**
     * 探测指定集合是否绑定了指定别名
     *
     * @param collectionName 集合名称
     * @param alias          逻辑别名
     * @return 是否已绑定
     */
    public boolean hasAlias(String collectionName, String alias) {
        if (!StringUtils.hasText(collectionName) || !StringUtils.hasText(alias)) {
            return false;
        }
        List<String> aliases = listAliases(collectionName);
        return aliases.contains(alias.trim());
    }

    // =========================================================================
    // 6. 极简数据写入与覆盖 (DML: Insert / Upsert Operations)
    // =========================================================================

    /**
     * 向指定集合写入字典数据 (支持多租户自动补全与动态字段)
     *
     * @param collectionName 集合名称
     * @param data           字典数据列表
     * @param partitionName  物理分区名称 (可为 null)
     */
    public void insert(String collectionName, List<Map<String, Object>> data, String partitionName) {
        Assert.hasText(collectionName, "collectionName must not be empty");
        if (data == null || data.isEmpty()) {
            return;
        }
        ensurePartitionExists(collectionName, partitionName);
        data.forEach(MilvusTenantHelper::fillRecordTenantField);
        execute("数据写入", c -> {
            c.insert(InsertReq.builder()
                    .collectionName(collectionName)
                    .partitionName(partitionName)
                    .data(MilvusEntityConverter.toJsonObjectList(data))
                    .build());
            return null;
        });
    }

    /**
     * 向指定集合写入字典数据 (默认分区)
     *
     * @param collectionName 集合名称
     * @param data           字典数据列表
     */
    public void insert(String collectionName, List<Map<String, Object>> data) {
        insert(collectionName, data, null);
    }

    /**
     * 写入强类型业务实体列表 (基于注解自动推导集合名、租户字段与向量字段)
     *
     * @param entities      实体列表
     * @param partitionName 物理分区名称 (可为 null)
     * @param <T>           实体泛型
     */
    public <T> void insertEntities(List<T> entities, String partitionName) {
        if (entities == null || entities.isEmpty()) {
            return;
        }
        Class<?> entityClass = entities.get(0).getClass();
        MilvusEntityMetadata metadata = MilvusEntityMetadata.of(entityClass);
        String collectionName = metadata.getCollectionName();
        ensurePartitionExists(collectionName, partitionName);
        entities.forEach(e -> MilvusTenantHelper.fillEntityTenantField(e, metadata, false));
        execute("实体数据写入", c -> {
            c.insert(InsertReq.builder()
                    .collectionName(collectionName)
                    .partitionName(partitionName)
                    .data(MilvusEntityConverter.entitiesToJsonObjectList(entities))
                    .build());
            return null;
        });
    }

    /**
     * 写入强类型业务实体列表 (默认分区)
     *
     * @param entities 实体列表
     * @param <T>      实体泛型
     */
    public <T> void insertEntities(List<T> entities) {
        insertEntities(entities, null);
    }

    /**
     * 向指定集合覆盖写入字典数据 (主键存在则更新，不存在则插入)
     *
     * @param collectionName 集合名称
     * @param data           字典数据列表
     * @param partitionName  物理分区名称 (可为 null)
     */
    public void upsert(String collectionName, List<Map<String, Object>> data, String partitionName) {
        Assert.hasText(collectionName, "collectionName must not be empty");
        if (data == null || data.isEmpty()) {
            return;
        }
        ensurePartitionExists(collectionName, partitionName);
        data.forEach(MilvusTenantHelper::fillRecordTenantField);
        execute("数据覆盖写入", c -> {
            c.upsert(UpsertReq.builder()
                    .collectionName(collectionName)
                    .partitionName(partitionName)
                    .data(MilvusEntityConverter.toJsonObjectList(data))
                    .build());
            return null;
        });
    }

    /**
     * 向指定集合覆盖写入字典数据 (默认分区)
     *
     * @param collectionName 集合名称
     * @param data           字典数据列表
     */
    public void upsert(String collectionName, List<Map<String, Object>> data) {
        upsert(collectionName, data, null);
    }

    /**
     * 覆盖写入强类型业务实体列表 (主键存在则更新，不存在则插入)
     *
     * @param entities      实体列表
     * @param partitionName 物理分区名称 (可为 null)
     * @param <T>           实体泛型
     */
    public <T> void upsertEntities(List<T> entities, String partitionName) {
        if (entities == null || entities.isEmpty()) {
            return;
        }
        Class<?> entityClass = entities.get(0).getClass();
        MilvusEntityMetadata metadata = MilvusEntityMetadata.of(entityClass);
        String collectionName = metadata.getCollectionName();
        ensurePartitionExists(collectionName, partitionName);
        entities.forEach(e -> MilvusTenantHelper.fillEntityTenantField(e, metadata, false));
        execute("实体数据覆盖写入", c -> {
            c.upsert(UpsertReq.builder()
                    .collectionName(collectionName)
                    .partitionName(partitionName)
                    .data(MilvusEntityConverter.entitiesToJsonObjectList(entities))
                    .build());
            return null;
        });
    }

    /**
     * 覆盖写入强类型业务实体列表 (默认分区)
     *
     * @param entities 实体列表
     * @param <T>      实体泛型
     */
    public <T> void upsertEntities(List<T> entities) {
        upsertEntities(entities, null);
    }

    // =========================================================================
    // 7. 极简数据删除 (Delete Operations)
    // =========================================================================

    /**
     * 根据主键 ID 删除集合中的单条数据 (支持物理分区指定)
     *
     * @param collectionName 集合名称
     * @param id             主键 ID
     * @param partitionName  物理分区名称 (可为 null)
     */
    public void deleteById(String collectionName, Object id, String partitionName) {
        Assert.notNull(id, "id must not be null");
        deleteByIds(collectionName, Collections.singletonList(id), partitionName);
    }

    /**
     * 根据主键 ID 删除集合中的单条数据
     *
     * @param collectionName 集合名称
     * @param id             主键 ID
     */
    public void deleteById(String collectionName, Object id) {
        deleteById(collectionName, id, null);
    }

    /**
     * 按主键 ID 列表删除集合中的数据 (多租户环境下自动绑定租户隔离过滤)
     *
     * @param collectionName 集合名称
     * @param ids            主键列表
     * @param partitionName  物理分区名称 (可为 null)
     */
    public void deleteByIds(String collectionName, List<?> ids, String partitionName) {
        Assert.hasText(collectionName, "collectionName must not be empty");
        if (ids == null || ids.isEmpty()) {
            return;
        }
        if (!TenantContextHolder.isIgnoreTenant() && StringUtils.hasText(TenantContextHolder.getTenantId())) {
            String primaryField;
            try {
                var desc = describeCollection(collectionName);
                primaryField = desc != null ? desc.getPrimaryFieldName() : null;
            } catch (Exception e) {
                throw new MilvusException("多租户安全拦截: 获取集合 [" + collectionName + "] 主键元数据失败，禁止执行无租户约束的裸 ID 删除", e);
            }
            if (!StringUtils.hasText(primaryField)) {
                throw new MilvusException("多租户安全拦截: 集合 [" + collectionName + "] 未定义主键，禁止执行无租户约束的裸 ID 删除");
            }
            String filter = MilvusExprHelper.in(primaryField, ids);
            deleteByExpr(collectionName, filter, partitionName);
            return;
        }
        execute("按主键删除数据", c -> {
            var reqBuilder = DeleteReq.builder()
                    .collectionName(collectionName)
                    .ids((List<Object>) ids);
            if (StringUtils.hasText(partitionName)) {
                reqBuilder.partitionName(partitionName);
            }
            c.delete(reqBuilder.build());
            return null;
        });
    }

    /**
     * 按主键 ID 列表删除集合中的数据
     *
     * @param collectionName 集合名称
     * @param ids            主键列表
     */
    public void deleteByIds(String collectionName, List<?> ids) {
        deleteByIds(collectionName, ids, null);
    }

    /**
     * 根据实体类型与主键 ID 删除单条数据 (支持物理分区指定)
     *
     * @param entityClass   实体类型
     * @param id            主键 ID
     * @param partitionName 物理分区名称 (可为 null)
     * @param <T>           实体泛型
     */
    public <T> void deleteById(Class<T> entityClass, Object id, String partitionName) {
        Assert.notNull(id, "id must not be null");
        deleteEntities(entityClass, Collections.singletonList(id), partitionName);
    }

    /**
     * 根据实体类型与主键 ID 删除单条数据
     *
     * @param entityClass 实体类型
     * @param id          主键 ID
     * @param <T>         实体泛型
     */
    public <T> void deleteById(Class<T> entityClass, Object id) {
        deleteById(entityClass, id, null);
    }

    /**
     * 按实体类型与主键 ID 列表删除数据
     *
     * @param entityClass   实体类
     * @param ids           主键列表
     * @param partitionName 物理分区名称 (可为 null)
     * @param <T>           实体泛型
     */
    public <T> void deleteEntities(Class<T> entityClass, List<?> ids, String partitionName) {
        Assert.notNull(entityClass, "entityClass must not be null");
        if (ids == null || ids.isEmpty()) {
            return;
        }
        MilvusEntityMetadata metadata = MilvusEntityMetadata.of(entityClass);
        String collectionName = metadata.getCollectionName();
        String primaryField = metadata.getIdField() != null ? metadata.getIdField().getFieldName() : "id";
        if (!TenantContextHolder.isIgnoreTenant() && StringUtils.hasText(TenantContextHolder.getTenantId())) {
            String filter = MilvusExprHelper.in(primaryField, ids);
            deleteByExpr(collectionName, filter, partitionName);
            return;
        }
        execute("按实体主键删除数据", c -> {
            var reqBuilder = DeleteReq.builder()
                    .collectionName(collectionName)
                    .ids((List<Object>) ids);
            if (StringUtils.hasText(partitionName)) {
                reqBuilder.partitionName(partitionName);
            }
            c.delete(reqBuilder.build());
            return null;
        });
    }

    /**
     * 按实体类型与主键 ID 列表删除数据
     *
     * @param entityClass 实体类
     * @param ids         主键列表
     * @param <T>         实体泛型
     */
    public <T> void deleteEntities(Class<T> entityClass, List<?> ids) {
        deleteEntities(entityClass, ids, null);
    }

    /**
     * 按标量表达式条件删除数据 (自动织入多租户防越权表达式)
     *
     * @param collectionName 集合名称
     * @param expr           过滤表达式
     * @param partitionName  物理分区名称 (可为 null)
     */
    public void deleteByExpr(String collectionName, String expr, String partitionName) {
        Assert.hasText(collectionName, "collectionName must not be empty");
        String safeFilter = MilvusTenantHelper.weaveTenantFilter(expr, null, false);
        Assert.hasText(safeFilter, "delete filter expression must not be empty");
        execute("按条件删除数据", c -> {
            var reqBuilder = DeleteReq.builder()
                    .collectionName(collectionName)
                    .filter(safeFilter);
            if (StringUtils.hasText(partitionName)) {
                reqBuilder.partitionName(partitionName);
            }
            c.delete(reqBuilder.build());
            return null;
        });
    }

    /**
     * 按标量表达式条件删除数据
     *
     * @param collectionName 集合名称
     * @param expr           过滤表达式
     */
    public void deleteByExpr(String collectionName, String expr) {
        deleteByExpr(collectionName, expr, null);
    }

    /**
     * 按实体类型与标量表达式条件删除数据
     *
     * @param entityClass   实体类
     * @param expr          过滤表达式
     * @param partitionName 物理分区名称 (可为 null)
     * @param <T>           实体泛型
     */
    public <T> void deleteEntitiesByExpr(Class<T> entityClass, String expr, String partitionName) {
        Assert.notNull(entityClass, "entityClass must not be null");
        MilvusEntityMetadata metadata = MilvusEntityMetadata.of(entityClass);
        deleteByExpr(metadata.getCollectionName(), expr, partitionName);
    }

    /**
     * 按实体类型与标量表达式条件删除数据
     *
     * @param entityClass 实体类
     * @param expr        过滤表达式
     * @param <T>         实体泛型
     */
    public <T> void deleteEntitiesByExpr(Class<T> entityClass, String expr) {
        deleteEntitiesByExpr(entityClass, expr, null);
    }

    // =========================================================================
    // 8. 向量相似度检索 (DQL: Vector Search Operations)
    // =========================================================================

    /**
     * 执行底层原生 SearchReq 相似度检索
     *
     * @param request SearchReq 请求对象
     * @return 官方 SearchResp 响应
     */
    public SearchResp search(SearchReq request) {
        Assert.notNull(request, "SearchReq must not be null");
        return execute("向量相似度检索", c -> c.search(request));
    }

    /**
     * 执行全量参数向量相似度检索并转换为标准化命中结果模型
     *
     * @param collectionName   集合名称
     * @param annsField        向量索引字段名称 (可为空，默认推导)
     * @param vector           查询向量
     * @param topK             最大召回命中数
     * @param expr             标量过滤表达式 (可为空)
     * @param outputFields     出参字段列表 (为空且指定 entityClass 时默认 "*")
     * @param consistencyLevel 一致性级别 (可为空，默认取配置项)
     * @param entityClass      实体类型 (若为空则只填充 rawData)
     * @param <T>              实体泛型
     * @return 命中结果列表
     */
    @SuppressWarnings("unchecked")
    public <T> List<MilvusHit<T>> search(String collectionName,
                                         String annsField,
                                         List<Float> vector,
                                         int topK,
                                         String expr,
                                         List<String> outputFields,
                                         MilvusConsistencyLevel consistencyLevel,
                                         Class<T> entityClass) {
        return search(collectionName, annsField, vector, topK, expr, outputFields, consistencyLevel, entityClass, null);
    }

    /**
     * 底层综合向量相似度检索 (支持显式指定租户字段名)
     *
     * @param collectionName   集合名称
     * @param annsField        向量检索字段
     * @param vector           查询向量
     * @param topK             召回条数
     * @param expr             标量表达式
     * @param outputFields     出参字段列表
     * @param consistencyLevel 一致性级别
     * @param entityClass      实体类型
     * @param tenantFieldName  租户字段名 (可选，无实体类时生效)
     * @param <T>              实体泛型
     * @return 命中结果列表
     */
    @SuppressWarnings("unchecked")
    public <T> List<MilvusHit<T>> search(String collectionName,
                                         String annsField,
                                         List<Float> vector,
                                         int topK,
                                         String expr,
                                         List<String> outputFields,
                                         MilvusConsistencyLevel consistencyLevel,
                                         Class<T> entityClass,
                                         String tenantFieldName) {
        MilvusSearchRequest<T> request = MilvusSearchRequest.<T>builder()
                .collectionName(collectionName)
                .annsField(annsField)
                .vector(vector)
                .topK(topK)
                .expr(expr)
                .outputFields(outputFields)
                .consistencyLevel(consistencyLevel)
                .entityClass(entityClass)
                .tenantFieldName(tenantFieldName);
        return search(request);
    }

    /**
     * 基于统一 Fluent 请求模型的单向量 ANN 相似度高级检索
     *
     * 支持标量过滤、GroupBy 去重聚合、Radius 与 RangeFilter 相似度绝对区间过滤、
     * 浅分页 Offset、专属检索超参数字典及多租户防护。
     *
     * @param request 单向量检索请求模型
     * @param <T>     实体泛型
     * @return 命中结果列表
     */
    @SuppressWarnings("unchecked")
    public <T> List<MilvusHit<T>> search(MilvusSearchRequest<T> request) {
        Assert.notNull(request, "Search request must not be null");
        request.validate();

        Class<T> entityClass = request.getEntityClass();
        MilvusEntityMetadata metadata = (entityClass != null && !Map.class.isAssignableFrom(entityClass))
                ? MilvusEntityMetadata.of(entityClass) : null;

        String collectionName = request.getCollectionName();
        if (!StringUtils.hasText(collectionName) && metadata != null) {
            collectionName = metadata.getCollectionName();
        }
        Assert.hasText(collectionName, "collectionName must not be empty");

        String effectiveTenantField = (metadata != null && metadata.getPartitionKeyField() != null)
                ? metadata.getPartitionKeyField().getFieldName()
                : request.getTenantFieldName();

        String safeFilter = metadata != null
                ? MilvusTenantHelper.weaveEntityTenantFilter(request.getExpr(), metadata, request.isIgnoreTenant())
                : MilvusTenantHelper.weaveTenantFilter(request.getExpr(), effectiveTenantField, request.isIgnoreTenant());

        MilvusConsistencyLevel level = request.getConsistencyLevel() != null
                ? request.getConsistencyLevel()
                : properties.getConsistencyLevel();

        List<String> effectiveOutputFields = request.getOutputFields();
        if ((effectiveOutputFields == null || effectiveOutputFields.isEmpty()) && metadata != null) {
            effectiveOutputFields = metadata.getFieldNames();
        }

        SearchReq.SearchReqBuilder<?, ?> builder = SearchReq.builder()
                .collectionName(collectionName)
                .data(Collections.singletonList(request.getVector()))
                .topK(request.getTopK())
                .consistencyLevel(level.getNativeLevel());

        String annsField = StringUtils.hasText(request.getAnnsField())
                ? request.getAnnsField()
                : (metadata != null && metadata.getVectorField() != null ? metadata.getVectorField().getFieldName() : null);
        if (StringUtils.hasText(annsField)) {
            builder.annsField(annsField);
        }

        if (StringUtils.hasText(safeFilter)) {
            builder.filter(safeFilter);
        }

        if (effectiveOutputFields != null && !effectiveOutputFields.isEmpty()) {
            builder.outputFields(effectiveOutputFields);
        }

        if (request.getPartitionNames() != null && !request.getPartitionNames().isEmpty()) {
            builder.partitionNames(request.getPartitionNames());
        }

        if (StringUtils.hasText(request.getGroupByFieldName())) {
            builder.groupByFieldName(request.getGroupByFieldName().trim());
        }

        if (request.getOffset() > 0) {
            builder.offset(request.getOffset());
        }

        if (request.getRoundDecimal() >= 0) {
            builder.roundDecimal(request.getRoundDecimal());
        }

        Map<String, Object> effectiveSearchParams = new HashMap<>();
        if (request.getSearchParams() != null && !request.getSearchParams().isEmpty()) {
            effectiveSearchParams.putAll(request.getSearchParams());
        }
        if (request.getRadius() != null) {
            effectiveSearchParams.put("radius", request.getRadius());
        }
        if (request.getRangeFilter() != null) {
            effectiveSearchParams.put("range_filter", request.getRangeFilter());
        }
        if (!effectiveSearchParams.isEmpty()) {
            builder.searchParams(effectiveSearchParams);
        }

        java.util.function.Supplier<SearchResp> searchAction = () -> search(builder.build());
        SearchResp resp;
        if (StringUtils.hasText(request.getDatabaseName())) {
            resp = useDatabase(request.getDatabaseName().trim(), searchAction);
        } else {
            resp = searchAction.get();
        }

        return extractHits(resp, entityClass);
    }

    @SuppressWarnings("unchecked")
    private <T> List<MilvusHit<T>> extractHits(SearchResp searchResp, Class<T> entityClass) {
        if (searchResp == null || searchResp.getSearchResults() == null || searchResp.getSearchResults().isEmpty()) {
            return Collections.emptyList();
        }
        List<SearchResp.SearchResult> firstResult = searchResp.getSearchResults().get(0);
        if (firstResult == null || firstResult.isEmpty()) {
            return Collections.emptyList();
        }
        List<MilvusHit<T>> hits = new ArrayList<>(firstResult.size());
        for (SearchResp.SearchResult r : firstResult) {
            Map<String, Object> entityMap = r.getEntity();
            T entity = null;
            if (entityClass != null) {
                if (Map.class.isAssignableFrom(entityClass)) {
                    entity = (T) entityMap;
                } else if (entityMap != null) {
                    entity = MilvusEntityConverter.mapToEntity(entityMap, entityClass);
                }
            } else if (entityMap != null) {
                entity = (T) entityMap;
            }
            hits.add(MilvusHit.<T>builder()
                    .id(r.getId())
                    .score(r.getScore())
                    .rawData(entityMap != null ? entityMap : Collections.emptyMap())
                    .entity(entity)
                    .build());
        }
        return hits;
    }

    /**
     * 基于实体类的强类型向量相似度检索 (支持显式指定一致性级别与标量过滤)
     *
     * @param entityClass      实体类
     * @param vector           查询向量
     * @param topK             召回数量
     * @param expr             标量表达式
     * @param consistencyLevel 一致性级别
     * @param <T>              实体泛型
     * @return 命中结果列表
     */
    public <T> List<MilvusHit<T>> search(Class<T> entityClass, List<Float> vector, int topK, String expr, MilvusConsistencyLevel consistencyLevel) {
        Assert.notNull(entityClass, "entityClass must not be null");
        MilvusEntityMetadata metadata = MilvusEntityMetadata.of(entityClass);
        String annsField = metadata.getVectorField() != null ? metadata.getVectorField().getFieldName() : null;
        return search(metadata.getCollectionName(), annsField, vector, topK, expr, null, consistencyLevel, entityClass);
    }

    /**
     * 基于实体类的强类型向量相似度检索 (支持标量过滤)
     *
     * @param entityClass 实体类
     * @param vector      查询向量
     * @param topK        召回数量
     * @param expr        标量表达式
     * @param <T>         实体泛型
     * @return 命中结果列表
     */
    public <T> List<MilvusHit<T>> search(Class<T> entityClass, List<Float> vector, int topK, String expr) {
        return search(entityClass, vector, topK, expr, null);
    }

    /**
     * 基于实体类的极简强类型向量相似度检索
     *
     * @param entityClass 实体类
     * @param vector      查询向量
     * @param topK        召回数量
     * @param <T>         实体泛型
     * @return 命中结果列表
     */
    public <T> List<MilvusHit<T>> search(Class<T> entityClass, List<Float> vector, int topK) {
        return search(entityClass, vector, topK, null, null);
    }

    /**
     * 基于集合名的字典式向量相似度检索 (支持字段与一致性级别定制)
     *
     * @param collectionName   集合名称
     * @param vector           查询向量
     * @param topK             召回数量
     * @param expr             标量表达式
     * @param outputFields     出参字段
     * @param consistencyLevel 一致性级别
     * @return 命中结果列表
     */
    public List<MilvusHit<Map<String, Object>>> search(String collectionName,
                                                       List<Float> vector,
                                                       int topK,
                                                       String expr,
                                                       List<String> outputFields,
                                                       MilvusConsistencyLevel consistencyLevel) {
        return search(collectionName, null, vector, topK, expr, outputFields, consistencyLevel, null);
    }

    /**
     * 基于集合名与指定向量列的字典式向量相似度检索 (支持字段与一致性级别定制)
     *
     * @param collectionName   集合名称
     * @param annsField        向量字段名称
     * @param vector           查询向量
     * @param topK             召回数量
     * @param expr             标量表达式
     * @param outputFields     出参字段
     * @param consistencyLevel 一致性级别
     * @return 命中结果列表
     */
    public List<MilvusHit<Map<String, Object>>> search(String collectionName,
                                                       String annsField,
                                                       List<Float> vector,
                                                       int topK,
                                                       String expr,
                                                       List<String> outputFields,
                                                       MilvusConsistencyLevel consistencyLevel) {
        return search(collectionName, annsField, vector, topK, expr, outputFields, consistencyLevel, null);
    }

    /**
     * 基于集合名与指定向量列的字典式向量相似度检索 (出参默认全部字段)
     *
     * @param collectionName   集合名称
     * @param annsField        向量字段名称
     * @param vector           查询向量
     * @param topK             召回数量
     * @param expr             标量表达式
     * @param consistencyLevel 一致性级别
     * @return 命中结果列表
     */
    public List<MilvusHit<Map<String, Object>>> search(String collectionName,
                                                       String annsField,
                                                       List<Float> vector,
                                                       int topK,
                                                       String expr,
                                                       MilvusConsistencyLevel consistencyLevel) {
        return search(collectionName, annsField, vector, topK, expr, null, consistencyLevel, null);
    }

    /**
     * 基于集合名的字典式向量相似度检索 (支持标量表达式)
     *
     * @param collectionName 集合名称
     * @param vector         查询向量
     * @param topK           召回数量
     * @param expr           标量表达式
     * @return 命中结果列表
     */
    public List<MilvusHit<Map<String, Object>>> search(String collectionName, List<Float> vector, int topK, String expr) {
        return search(collectionName, null, vector, topK, expr, null, null, null);
    }

    /**
     * 基于集合名的极简字典式向量相似度检索
     *
     * @param collectionName 集合名称
     * @param vector         查询向量
     * @param topK           召回数量
     * @return 命中结果列表
     */
    public List<MilvusHit<Map<String, Object>>> search(String collectionName, List<Float> vector, int topK) {
        return search(collectionName, null, vector, topK, null, null, null, null);
    }

    // =========================================================================
    // 9. 标量条件查询 (DQL: Scalar Query Operations)
    // =========================================================================

    /**
     * 执行底层原生 QueryReq 标量查询
     *
     * @param request QueryReq 请求对象
     * @return 官方 QueryResp 响应
     */
    public QueryResp query(QueryReq request) {
        Assert.notNull(request, "QueryReq must not be null");
        return execute("标量条件查询", c -> c.query(request));
    }

    /**
     * 按集合名与标量表达式查询字典数据 (支持多租户安全织入与出参指定)
     *
     * @param collectionName   集合名称
     * @param expr             过滤表达式
     * @param outputFields     出参字段列表
     * @param consistencyLevel 一致性级别 (可为 null)
     * @return 字典结果列表
     */
    public List<Map<String, Object>> query(String collectionName,
                                           String expr,
                                           List<String> outputFields,
                                           MilvusConsistencyLevel consistencyLevel) {
        return queryInternal(collectionName, expr, outputFields, consistencyLevel, null);
    }

    private List<Map<String, Object>> queryInternal(String collectionName,
                                                    String expr,
                                                    List<String> outputFields,
                                                    MilvusConsistencyLevel consistencyLevel,
                                                    MilvusEntityMetadata metadata) {
        Assert.hasText(collectionName, "collectionName must not be empty");
        String safeFilter = metadata != null
                ? MilvusTenantHelper.weaveEntityTenantFilter(expr, metadata, false)
                : MilvusTenantHelper.weaveTenantFilter(expr, null, false);
        MilvusConsistencyLevel level = consistencyLevel != null ? consistencyLevel : properties.getConsistencyLevel();

        QueryReq.QueryReqBuilder<?, ?> builder = QueryReq.builder()
                .collectionName(collectionName)
                .consistencyLevel(level.getNativeLevel());

        if (StringUtils.hasText(safeFilter)) {
            builder.filter(safeFilter);
        }
        if (outputFields != null && !outputFields.isEmpty()) {
            builder.outputFields(outputFields);
        } else {
            builder.outputFields(Collections.singletonList("*"));
        }

        QueryResp resp = query(builder.build());
        if (resp == null || resp.getQueryResults() == null || resp.getQueryResults().isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> results = new ArrayList<>(resp.getQueryResults().size());
        for (QueryResp.QueryResult r : resp.getQueryResults()) {
            if (r.getEntity() != null) {
                results.add(r.getEntity());
            }
        }
        return results;
    }

    /**
     * 按集合名与标量表达式查询字典数据
     *
     * @param collectionName 集合名称
     * @param expr           过滤表达式
     * @return 字典结果列表
     */
    public List<Map<String, Object>> query(String collectionName, String expr) {
        return query(collectionName, expr, null, null);
    }

    /**
     * 按实体类型与标量表达式查询业务实体列表 (支持一致性级别指定)
     *
     * @param entityClass      实体类型
     * @param expr             过滤表达式
     * @param consistencyLevel 一致性级别
     * @param <T>              实体泛型
     * @return 实体列表
     */
    public <T> List<T> query(Class<T> entityClass, String expr, MilvusConsistencyLevel consistencyLevel) {
        Assert.notNull(entityClass, "entityClass must not be null");
        MilvusEntityMetadata metadata = MilvusEntityMetadata.of(entityClass);
        List<Map<String, Object>> list = queryInternal(metadata.getCollectionName(), expr, metadata.getFieldNames(), consistencyLevel, metadata);
        return MilvusEntityConverter.mapListToEntities(list, entityClass);
    }

    /**
     * 按实体类型与标量表达式查询业务实体列表
     *
     * @param entityClass 实体类型
     * @param expr        过滤表达式
     * @param <T>         实体泛型
     * @return 实体列表
     */
    public <T> List<T> query(Class<T> entityClass, String expr) {
        return query(entityClass, expr, null);
    }

    // =========================================================================
    // 10. 主键点查与轻量探测 (Point Lookup & Probing Operations)
    // =========================================================================

    /**
     * 按主键精确点查单条记录 (自动防御跨租户越权探测)
     *
     * @param collectionName 集合名称
     * @param id             主键 ID
     * @param outputFields   出参字段列表
     * @return 记录字典，若不存在或跨租户越权则返回 null
     */
    public Map<String, Object> getById(String collectionName, Object id, List<String> outputFields) {
        return getById(collectionName, id, outputFields, MilvusTenantHelper.DEFAULT_TENANT_FIELD);
    }

    /**
     * 按主键精确点查单条记录 (支持显式指定租户字段名)
     *
     * @param collectionName  集合名称
     * @param id              主键 ID
     * @param outputFields    出参字段列表
     * @param tenantFieldName 租户字段名称 (为空时默认为 "tenant_id")
     * @return 记录字典，若不存在或跨租户越权则返回 null
     */
    public Map<String, Object> getById(String collectionName, Object id, List<String> outputFields, String tenantFieldName) {
        Assert.hasText(collectionName, "collectionName must not be empty");
        Assert.notNull(id, "id must not be null");
        List<String> fields = outputFields;
        if (fields == null || fields.isEmpty() || fields.contains("*")) {
            try {
                var desc = describeCollection(collectionName);
                if (desc != null && desc.getFieldNames() != null && !desc.getFieldNames().isEmpty()) {
                    fields = desc.getFieldNames();
                }
            } catch (Exception ignored) {
            }
        }

        String effectiveTenantField = StringUtils.hasText(tenantFieldName) ? tenantFieldName.trim() : MilvusTenantHelper.DEFAULT_TENANT_FIELD;
        // 多租户安全防线：若处于租户隔离上下文，强制将租户字段纳入出参以便后置严格验权
        boolean removeTenantField = false;
        String tenantId = !TenantContextHolder.isIgnoreTenant() ? TenantContextHolder.getTenantId() : null;
        if (StringUtils.hasText(tenantId)) {
            if (fields != null && !fields.isEmpty() && !fields.contains("*")) {
                if (!fields.contains(effectiveTenantField)) {
                    fields = new ArrayList<>(fields);
                    fields.add(effectiveTenantField);
                    removeTenantField = true;
                }
            }
        }

        final List<String> finalFields = fields;
        final boolean needRemoveTenant = removeTenantField;
        return execute("按主键点查", c -> {
            var getReqBuilder = GetReq.builder()
                    .collectionName(collectionName)
                    .ids(Collections.singletonList(id));
            if (finalFields != null && !finalFields.isEmpty()) {
                getReqBuilder.outputFields(finalFields);
            }
            var resp = c.get(getReqBuilder.build());
            if (resp != null && resp.getGetResults() != null && !resp.getGetResults().isEmpty()) {
                Map<String, Object> entity = resp.getGetResults().get(0).getEntity();
                if (entity != null && StringUtils.hasText(tenantId)) {
                    Object entityTenant = entity.get(effectiveTenantField);
                    // 严格越权防线：租户字段缺失或与当前租户不匹配一律视为无权读取并返回 null
                    if (entityTenant == null || !tenantId.trim().equals(String.valueOf(entityTenant).trim())) {
                        return null;
                    }
                    if (needRemoveTenant) {
                        entity.remove(effectiveTenantField);
                    }
                }
                return entity;
            }
            return null;
        });
    }

    /**
     * 按主键精确点查单条记录
     *
     * @param collectionName 集合名称
     * @param id             主键 ID
     * @return 记录字典，若不存在则返回 null
     */
    public Map<String, Object> getById(String collectionName, Object id) {
        return getById(collectionName, id, null);
    }

    /**
     * 按实体类型与主键精确点查单个实体
     *
     * @param entityClass 实体类型
     * @param id          主键 ID
     * @param <T>         实体泛型
     * @return 业务实体，若不存在则返回 null
     */
    public <T> T getById(Class<T> entityClass, Object id) {
        Assert.notNull(entityClass, "entityClass must not be null");
        Assert.notNull(id, "id must not be null");
        MilvusEntityMetadata metadata = MilvusEntityMetadata.of(entityClass);
        String tenantField = (metadata.getPartitionKeyField() != null)
                ? metadata.getPartitionKeyField().getFieldName()
                : MilvusTenantHelper.DEFAULT_TENANT_FIELD;
        Map<String, Object> map = getById(metadata.getCollectionName(), id, metadata.getFieldNames(), tenantField);
        return MilvusEntityConverter.mapToEntity(map, entityClass);
    }

    /**
     * 按主键批量点查记录列表 (自动防御跨租户越权探测)
     *
     * @param collectionName 集合名称
     * @param ids            主键列表
     * @param outputFields   出参字段列表
     * @return 记录字典列表
     */
    public List<Map<String, Object>> getByIds(String collectionName, List<?> ids, List<String> outputFields) {
        return getByIds(collectionName, ids, outputFields, MilvusTenantHelper.DEFAULT_TENANT_FIELD);
    }

    /**
     * 按主键批量点查记录列表 (支持显式指定租户字段名)
     *
     * @param collectionName  集合名称
     * @param ids             主键列表
     * @param outputFields    出参字段列表
     * @param tenantFieldName 租户字段名称 (为空时默认为 "tenant_id")
     * @return 记录字典列表
     */
    public List<Map<String, Object>> getByIds(String collectionName, List<?> ids, List<String> outputFields, String tenantFieldName) {
        Assert.hasText(collectionName, "collectionName must not be empty");
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> fields = outputFields;
        if (fields == null || fields.isEmpty() || fields.contains("*")) {
            try {
                var desc = describeCollection(collectionName);
                if (desc != null && desc.getFieldNames() != null && !desc.getFieldNames().isEmpty()) {
                    fields = desc.getFieldNames();
                }
            } catch (Exception ignored) {
            }
        }

        String effectiveTenantField = StringUtils.hasText(tenantFieldName) ? tenantFieldName.trim() : MilvusTenantHelper.DEFAULT_TENANT_FIELD;
        // 多租户安全防线：若处于租户隔离上下文，强制将租户字段纳入出参以便后置严格验权
        boolean removeTenantField = false;
        String tenantId = !TenantContextHolder.isIgnoreTenant() ? TenantContextHolder.getTenantId() : null;
        if (StringUtils.hasText(tenantId)) {
            if (fields != null && !fields.isEmpty() && !fields.contains("*")) {
                if (!fields.contains(effectiveTenantField)) {
                    fields = new ArrayList<>(fields);
                    fields.add(effectiveTenantField);
                    removeTenantField = true;
                }
            }
        }

        final List<String> finalFields = fields;
        final boolean needRemoveTenant = removeTenantField;
        return execute("按主键批量点查", c -> {
            var getReqBuilder = GetReq.builder()
                    .collectionName(collectionName)
                    .ids((List<Object>) ids);
            if (finalFields != null && !finalFields.isEmpty()) {
                getReqBuilder.outputFields(finalFields);
            }
            var resp = c.get(getReqBuilder.build());
            if (resp != null && resp.getGetResults() != null && !resp.getGetResults().isEmpty()) {
                List<Map<String, Object>> list = new ArrayList<>(resp.getGetResults().size());
                for (var r : resp.getGetResults()) {
                    Map<String, Object> entity = r.getEntity();
                    if (entity != null) {
                        if (StringUtils.hasText(tenantId)) {
                            Object entityTenant = entity.get(effectiveTenantField);
                            // 严格越权防线：租户字段缺失或与当前租户不匹配一律跳过
                            if (entityTenant == null || !tenantId.trim().equals(String.valueOf(entityTenant).trim())) {
                                continue;
                            }
                            if (needRemoveTenant) {
                                entity.remove(effectiveTenantField);
                            }
                        }
                        list.add(entity);
                    }
                }
                return list;
            }
            return Collections.emptyList();
        });
    }

    /**
     * 按主键批量点查记录列表
     *
     * @param collectionName 集合名称
     * @param ids            主键列表
     * @return 记录字典列表
     */
    public List<Map<String, Object>> getByIds(String collectionName, List<?> ids) {
        return getByIds(collectionName, ids, null);
    }

    /**
     * 按实体类型与主键列表批量点查业务实体
     *
     * @param entityClass 实体类型
     * @param ids         主键列表
     * @param <T>         实体泛型
     * @return 业务实体列表
     */
    public <T> List<T> getByIds(Class<T> entityClass, List<?> ids) {
        Assert.notNull(entityClass, "entityClass must not be null");
        MilvusEntityMetadata metadata = MilvusEntityMetadata.of(entityClass);
        String tenantField = (metadata.getPartitionKeyField() != null)
                ? metadata.getPartitionKeyField().getFieldName()
                : MilvusTenantHelper.DEFAULT_TENANT_FIELD;
        List<Map<String, Object>> list = getByIds(metadata.getCollectionName(), ids, metadata.getFieldNames(), tenantField);
        return MilvusEntityConverter.mapListToEntities(list, entityClass);
    }

    /**
     * 判定指定主键是否存在
     *
     * @param collectionName 集合名称
     * @param id             主键 ID
     * @return 是否存在
     */
    public boolean existsById(String collectionName, Object id) {
        return getById(collectionName, id) != null;
    }

    /**
     * 按实体类型判定指定主键是否存在
     *
     * @param entityClass 实体类型
     * @param id          主键 ID
     * @return 是否存在
     */
    public boolean existsById(Class<?> entityClass, Object id) {
        Assert.notNull(entityClass, "entityClass must not be null");
        MilvusEntityMetadata metadata = MilvusEntityMetadata.of(entityClass);
        return existsById(metadata.getCollectionName(), id);
    }

    /**
     * 统计集合符合条件的实体数量 (无条件时极速读取统计，有条件时聚合统计)
     *
     * @param collectionName 集合名称
     * @param expr           过滤表达式 (可为 null)
     * @return 数量统计
     */
    public long count(String collectionName, String expr) {
        return countInternal(collectionName, expr, null);
    }

    private long countInternal(String collectionName, String expr, MilvusEntityMetadata metadata) {
        Assert.hasText(collectionName, "collectionName must not be empty");
        String safeFilter = metadata != null
                ? MilvusTenantHelper.weaveEntityTenantFilter(expr, metadata, false)
                : MilvusTenantHelper.weaveTenantFilter(expr, null, false);
        if (!StringUtils.hasText(safeFilter)) {
            try {
                var stats = getCollectionStats(collectionName);
                if (stats != null && stats.getNumOfEntities() != null) {
                    return stats.getNumOfEntities();
                }
            } catch (Exception ignored) {
                // 回退至 query count(*)
            }
        }
        return execute("统计实体数量", c -> {
            QueryReq.QueryReqBuilder<?, ?> builder = QueryReq.builder()
                    .collectionName(collectionName)
                    .outputFields(Collections.singletonList("count(*)"));
            if (StringUtils.hasText(safeFilter)) {
                builder.filter(safeFilter);
            }
            var resp = c.query(builder.build());
            if (resp != null && resp.getQueryResults() != null && !resp.getQueryResults().isEmpty()) {
                Map<String, Object> entity = resp.getQueryResults().get(0).getEntity();
                if (entity != null) {
                    Object cnt = entity.get("count(*)");
                    if (cnt instanceof Number num) {
                        return num.longValue();
                    }
                }
            }
            return 0L;
        });
    }

    /**
     * 统计集合中的全部实体数量
     *
     * @param collectionName 集合名称
     * @return 集合总实体数
     */
    public long count(String collectionName) {
        return count(collectionName, null);
    }

    /**
     * 按实体类型统计符合条件的实体数量
     *
     * @param entityClass 实体类型
     * @param expr        过滤表达式
     * @param <T>         实体泛型
     * @return 数量统计
     */
    public <T> long count(Class<T> entityClass, String expr) {
        Assert.notNull(entityClass, "entityClass must not be null");
        MilvusEntityMetadata metadata = MilvusEntityMetadata.of(entityClass);
        return countInternal(metadata.getCollectionName(), expr, metadata);
    }

    /**
     * 按实体类型统计全部实体数量
     *
     * @param entityClass 实体类型
     * @param <T>         实体泛型
     * @return 集合总实体数
     */
    public <T> long count(Class<T> entityClass) {
        return count(entityClass, null);
    }

    // =========================================================================
    // 11. 海量数据透明分批切片摄取 (Batch Slicing Ingestion Operations)
    // =========================================================================

    /**
     * 分批切片写入海量字典数据 (防 OOM 与 gRPC 帧超限，结合指数退避重试)
     *
     * @param collectionName 集合名称
     * @param data           字典数据列表
     * @param partitionName  物理分区名称 (可为 null)
     * @param batchSize      每批切片大小 (默认 1000)
     */
    public void insertBatch(String collectionName, List<Map<String, Object>> data, String partitionName, int batchSize) {
        if (data == null || data.isEmpty()) {
            return;
        }
        int effectiveBatchSize = batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
        BatchSliceUtil.forEachSlice(data, effectiveBatchSize, slice ->
                RetryUtil.execute(() -> insert(collectionName, slice, partitionName), DEFAULT_RETRY_CONFIG));
    }

    /**
     * 分批切片写入字典数据 (默认分区)
     *
     * @param collectionName 集合名称
     * @param data           字典数据列表
     * @param batchSize      每批切片大小
     */
    public void insertBatch(String collectionName, List<Map<String, Object>> data, int batchSize) {
        insertBatch(collectionName, data, null, batchSize);
    }

    /**
     * 分批切片写入字典数据 (指定分区与默认切片大小 1000)
     *
     * @param collectionName 集合名称
     * @param data           字典数据列表
     * @param partitionName  物理分区名称 (可为 null)
     */
    public void insertBatch(String collectionName, List<Map<String, Object>> data, String partitionName) {
        insertBatch(collectionName, data, partitionName, DEFAULT_BATCH_SIZE);
    }

    /**
     * 分批切片写入字典数据 (默认分区与默认切片大小 1000)
     *
     * @param collectionName 集合名称
     * @param data           字典数据列表
     */
    public void insertBatch(String collectionName, List<Map<String, Object>> data) {
        insertBatch(collectionName, data, null, DEFAULT_BATCH_SIZE);
    }

    /**
     * 分批切片写入海量业务实体数据 (防 OOM 与 gRPC 帧超限，结合指数退避重试)
     *
     * @param entities      实体列表
     * @param partitionName 物理分区名称 (可为 null)
     * @param batchSize     每批切片大小 (默认 1000)
     * @param <T>           实体泛型
     */
    public <T> void insertBatchEntities(List<T> entities, String partitionName, int batchSize) {
        if (entities == null || entities.isEmpty()) {
            return;
        }
        int effectiveBatchSize = batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
        BatchSliceUtil.forEachSlice(entities, effectiveBatchSize, slice ->
                RetryUtil.execute(() -> insertEntities(slice, partitionName), DEFAULT_RETRY_CONFIG));
    }

    /**
     * 分批切片写入业务实体数据 (默认分区)
     *
     * @param entities  实体列表
     * @param batchSize 每批切片大小
     * @param <T>       实体泛型
     */
    public <T> void insertBatchEntities(List<T> entities, int batchSize) {
        insertBatchEntities(entities, null, batchSize);
    }

    /**
     * 分批切片写入业务实体数据 (默认分区与默认切片大小 1000)
     *
     * @param entities 实体列表
     * @param <T>      实体泛型
     */
    public <T> void insertBatchEntities(List<T> entities) {
        insertBatchEntities(entities, null, DEFAULT_BATCH_SIZE);
    }

    /**
     * 分批切片覆盖写入海量字典数据 (防 OOM 与 gRPC 帧超限，结合指数退避重试)
     *
     * @param collectionName 集合名称
     * @param data           字典数据列表
     * @param partitionName  物理分区名称 (可为 null)
     * @param batchSize      每批切片大小 (默认 1000)
     */
    public void upsertBatch(String collectionName, List<Map<String, Object>> data, String partitionName, int batchSize) {
        if (data == null || data.isEmpty()) {
            return;
        }
        int effectiveBatchSize = batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
        BatchSliceUtil.forEachSlice(data, effectiveBatchSize, slice ->
                RetryUtil.execute(() -> upsert(collectionName, slice, partitionName), DEFAULT_RETRY_CONFIG));
    }

    /**
     * 分批切片覆盖写入字典数据 (默认分区)
     *
     * @param collectionName 集合名称
     * @param data           字典数据列表
     * @param batchSize      每批切片大小
     */
    public void upsertBatch(String collectionName, List<Map<String, Object>> data, int batchSize) {
        upsertBatch(collectionName, data, null, batchSize);
    }

    /**
     * 分批切片覆盖写入字典数据 (默认分区与默认切片大小 1000)
     *
     * @param collectionName 集合名称
     * @param data           字典数据列表
     */
    public void upsertBatch(String collectionName, List<Map<String, Object>> data) {
        upsertBatch(collectionName, data, null, DEFAULT_BATCH_SIZE);
    }

    /**
     * 分批切片覆盖写入海量业务实体数据 (防 OOM 与 gRPC 帧超限，结合指数退避重试)
     *
     * @param entities      实体列表
     * @param partitionName 物理分区名称 (可为 null)
     * @param batchSize     每批切片大小 (默认 1000)
     * @param <T>           实体泛型
     */
    public <T> void upsertBatchEntities(List<T> entities, String partitionName, int batchSize) {
        if (entities == null || entities.isEmpty()) {
            return;
        }
        int effectiveBatchSize = batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
        BatchSliceUtil.forEachSlice(entities, effectiveBatchSize, slice ->
                RetryUtil.execute(() -> upsertEntities(slice, partitionName), DEFAULT_RETRY_CONFIG));
    }

    /**
     * 分批切片覆盖写入业务实体数据 (默认分区)
     *
     * @param entities  实体列表
     * @param batchSize 每批切片大小
     * @param <T>       实体泛型
     */
    public <T> void upsertBatchEntities(List<T> entities, int batchSize) {
        upsertBatchEntities(entities, null, batchSize);
    }

    /**
     * 分批切片覆盖写入业务实体数据 (默认分区与默认切片大小 1000)
     *
     * @param entities 实体列表
     * @param <T>      实体泛型
     */
    public <T> void upsertBatchEntities(List<T> entities) {
        upsertBatchEntities(entities, null, DEFAULT_BATCH_SIZE);
    }

    private void ensurePartitionExists(String collectionName, String partitionName) {
        if (partitionName != null && properties.isAutoCreatePartition()) {
            if (!hasPartition(collectionName, partitionName)) {
                log.info("时序物理分区自动创建: 集合 [{}] 分区 [{}] 不存在，正在自动创建", collectionName, partitionName);
                createPartition(collectionName, partitionName);
            }
        }
    }

    // =========================================================================
    // 12. 多向量混合检索 (Hybrid Search Operations)
    // =========================================================================

    /**
     * 执行底层原生 HybridSearchReq 多向量混合检索
     *
     * @param request HybridSearchReq 请求对象
     * @return 官方 SearchResp 响应
     */
    public SearchResp hybridSearch(HybridSearchReq request) {
        Assert.notNull(request, "HybridSearchReq must not be null");
        return execute("多向量混合检索", c -> c.hybridSearch(request));
    }

    /**
     * 执行高级链式多向量混合检索 (融合 RRF/Weighted 重排并返回强类型标准化命中结果)
     *
     * @param request MilvusHybridSearchRequest 请求对象
     * @param <T>     实体泛型
     * @return 强类型命中结果列表
     */
    public <T> List<MilvusHit<T>> hybridSearch(MilvusHybridSearchRequest<T> request) {
        Assert.notNull(request, "MilvusHybridSearchRequest must not be null");
        Assert.hasText(request.getCollectionName(), "collectionName must not be empty");
        Assert.notEmpty(request.getSearchRequests(), "searchRequests must not be empty");
        Assert.isTrue(request.getTopK() > 0, "topK must be positive");

        String effectiveDb = StringUtils.hasText(request.getDatabaseName()) ? request.getDatabaseName() : resolveCurrentDatabase();
        MilvusConsistencyLevel level = request.getConsistencyLevel() != null ? request.getConsistencyLevel() : properties.getConsistencyLevel();
        BaseRanker ranker = request.getRanker() != null ? request.getRanker() : ReRanker.rrf();

        List<AnnSearchReq> annSearchReqs = new ArrayList<>(request.getSearchRequests().size());
        for (AnnSearch annSearch : request.getSearchRequests()) {
            String wovenExpr = MilvusTenantHelper.weaveTenantFilter(annSearch.getExpr(), null, request.isIgnoreTenant());
            annSearchReqs.add(annSearch.toAnnSearchReq(wovenExpr));
        }

        List<String> effectiveOutFields = request.getOutputFields();
        if ((effectiveOutFields == null || effectiveOutFields.isEmpty()) && request.getEntityClass() != null && !Map.class.isAssignableFrom(request.getEntityClass())) {
            effectiveOutFields = MilvusEntityMetadata.of(request.getEntityClass()).getFieldNames();
        }

        HybridSearchReq.HybridSearchReqBuilder<?, ?> builder = HybridSearchReq.builder()
                .collectionName(request.getCollectionName())
                .searchRequests(annSearchReqs)
                .ranker(ranker)
                .topK(request.getTopK())
                .consistencyLevel(level.getNativeLevel());

        if (StringUtils.hasText(effectiveDb)) {
            builder.databaseName(effectiveDb);
        }
        if (request.getPartitionNames() != null && !request.getPartitionNames().isEmpty()) {
            builder.partitionNames(request.getPartitionNames());
        }
        if (effectiveOutFields != null && !effectiveOutFields.isEmpty()) {
            builder.outFields(effectiveOutFields);
        }
        if (request.getRoundDecimal() >= 0) {
            builder.roundDecimal(request.getRoundDecimal());
        }

        SearchResp resp = hybridSearch(builder.build());
        return extractHits(resp, request.getEntityClass());
    }

    // =========================================================================
    // 13. 大数据游标流式遍历 (Streaming Query Operations)
    // =========================================================================

    /**
     * 基于官方 QueryIterator 执行大数据游标流式遍历 (突破 16384 offset 深度分页限制，零 OOM)
     *
     * @param collectionName 集合名称
     * @param expr           过滤表达式 (可为 null)
     * @param outputFields   出参字段列表 (为空默认 "*")
     * @param batchSize      每批游标拉取大小 (默认 1000)
     * @param batchConsumer  每批字典数据消费回调
     */
    public void streamingQuery(String collectionName,
                               String expr,
                               List<String> outputFields,
                               int batchSize,
                               Consumer<List<Map<String, Object>>> batchConsumer) {
        streamingQuery(collectionName, expr, outputFields, batchSize, null, batchConsumer);
    }

    /**
     * 基于官方 QueryIterator 执行大数据游标流式遍历 (支持实体元数据多租户推导)
     *
     * @param collectionName 集合名称
     * @param expr           过滤表达式
     * @param outputFields   投影字段列表 (为空默认 "*")
     * @param batchSize      每批游标拉取大小 (默认 1000)
     * @param metadata       实体元数据 (可为 null)
     * @param batchConsumer  每批字典数据消费回调
     */
    public void streamingQuery(String collectionName,
                               String expr,
                               List<String> outputFields,
                               int batchSize,
                               MilvusEntityMetadata metadata,
                               Consumer<List<Map<String, Object>>> batchConsumer) {
        Assert.hasText(collectionName, "collectionName must not be empty");
        Assert.notNull(batchConsumer, "batchConsumer must not be null");

        String safeFilter = metadata != null
                ? MilvusTenantHelper.weaveEntityTenantFilter(expr, metadata, false)
                : MilvusTenantHelper.weaveTenantFilter(expr, null, false);
        int effectiveBatchSize = batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
        List<String> effectiveOutFields = outputFields != null && !outputFields.isEmpty() ? outputFields : Collections.singletonList("*");
        String effectiveDb = resolveCurrentDatabase();

        QueryIteratorReq.QueryIteratorReqBuilder<?, ?> builder = QueryIteratorReq.builder()
                .collectionName(collectionName)
                .batchSize(effectiveBatchSize)
                .outputFields(effectiveOutFields)
                .consistencyLevel(properties.getConsistencyLevel().getNativeLevel());

        if (StringUtils.hasText(effectiveDb)) {
            builder.databaseName(effectiveDb);
        }
        if (StringUtils.hasText(safeFilter)) {
            builder.expr(safeFilter);
        }

        execute("游标流式遍历", c -> {
            QueryIterator iterator = null;
            try {
                iterator = c.queryIterator(builder.build());
                while (true) {
                    List<QueryResultsWrapper.RowRecord> records = iterator.next();
                    if (records == null || records.isEmpty()) {
                        break;
                    }
                    List<Map<String, Object>> batch = new ArrayList<>(records.size());
                    String tenantId = !TenantContextHolder.isIgnoreTenant() ? TenantContextHolder.getTenantId() : null;
                    String tenantField = (metadata != null && metadata.getPartitionKeyField() != null)
                            ? metadata.getPartitionKeyField().getFieldName()
                            : MilvusTenantHelper.DEFAULT_TENANT_FIELD;
                    for (QueryResultsWrapper.RowRecord record : records) {
                        Map<String, Object> map = record.getFieldValues();
                        if (map != null) {
                            if (StringUtils.hasText(tenantId)) {
                                Object rowTenant = map.get(tenantField);
                                if (rowTenant == null || !tenantId.trim().equals(String.valueOf(rowTenant).trim())) {
                                    continue;
                                }
                            }
                            batch.add(map);
                        }
                    }
                    if (!batch.isEmpty()) {
                        batchConsumer.accept(batch);
                    }
                }
                return null;
            } finally {
                if (iterator != null) {
                    try {
                        iterator.close();
                    } catch (Exception e) {
                        log.warn("关闭 QueryIterator 游标异常: {}", e.getMessage());
                    }
                }
            }
        });
    }

    /**
     * 基于官方 QueryIterator 执行大数据游标流式遍历 (默认拉取批次大小 1000)
     *
     * @param collectionName 集合名称
     * @param expr           过滤表达式
     * @param batchConsumer  每批字典数据消费回调
     */
    public void streamingQuery(String collectionName,
                               String expr,
                               Consumer<List<Map<String, Object>>> batchConsumer) {
        streamingQuery(collectionName, expr, null, DEFAULT_BATCH_SIZE, batchConsumer);
    }

    /**
     * 基于官方 QueryIterator 执行大数据游标流式遍历 (自定义批次大小)
     *
     * @param collectionName 集合名称
     * @param expr           过滤表达式
     * @param batchSize      每批游标拉取大小
     * @param batchConsumer  每批字典数据消费回调
     */
    public void streamingQuery(String collectionName,
                               String expr,
                               int batchSize,
                               Consumer<List<Map<String, Object>>> batchConsumer) {
        streamingQuery(collectionName, expr, null, batchSize, batchConsumer);
    }

    /**
     * 基于官方 QueryIterator 执行强类型实体大数据游标流式遍历
     *
     * @param entityClass   实体类型
     * @param expr          过滤表达式
     * @param batchSize     每批拉取大小
     * @param batchConsumer 每批实体列表消费回调
     * @param <T>           实体泛型
     */
    public <T> void streamingQueryEntities(Class<T> entityClass,
                                           String expr,
                                           int batchSize,
                                           Consumer<List<T>> batchConsumer) {
        Assert.notNull(entityClass, "entityClass must not be null");
        Assert.notNull(batchConsumer, "batchConsumer must not be null");
        MilvusEntityMetadata metadata = MilvusEntityMetadata.of(entityClass);
        streamingQuery(metadata.getCollectionName(), expr, null, batchSize, metadata, batchMaps -> {
            List<T> entities = MilvusEntityConverter.mapListToEntities(batchMaps, entityClass);
            if (!entities.isEmpty()) {
                batchConsumer.accept(entities);
            }
        });
    }

    /**
     * 基于官方 QueryIterator 执行强类型实体大数据游标流式遍历 (默认批次大小 1000)
     *
     * @param entityClass   实体类型
     * @param expr          过滤表达式
     * @param batchConsumer 每批实体列表消费回调
     * @param <T>           实体泛型
     */
    public <T> void streamingQueryEntities(Class<T> entityClass,
                                           String expr,
                                           Consumer<List<T>> batchConsumer) {
        streamingQueryEntities(entityClass, expr, DEFAULT_BATCH_SIZE, batchConsumer);
    }

    // =========================================================================
    // 14. 动态切库治理与上下文透传 (Database Routing Operations)
    // =========================================================================

    /**
     * 获取当前生效的逻辑数据库名称 (优先自 ThreadLocal 上下文提取，否则回退全局默认配置)
     *
     * @return 逻辑数据库名称
     */
    public String resolveCurrentDatabase() {
        String db = MilvusContextHolder.getDatabase();
        if (StringUtils.hasText(db)) {
            return db.trim();
        }
        return properties.getDatabase();
    }

    /**
     * 在指定的逻辑数据库上下文中执行闭包任务 (在 finally 块中强制恢复现场，保障线程绝对安全)
     *
     * @param databaseName 目标数据库名称
     * @param action       待执行闭包动作
     * @param <T>          返回值泛型
     * @return 执行结果
     */
    public <T> T useDatabase(String databaseName, Supplier<T> action) {
        Assert.hasText(databaseName, "databaseName must not be empty");
        Assert.notNull(action, "action must not be null");
        String previousDb = MilvusContextHolder.getDatabase();
        try {
            MilvusContextHolder.setDatabase(databaseName.trim());
            return action.get();
        } finally {
            if (previousDb != null) {
                MilvusContextHolder.setDatabase(previousDb);
            } else {
                MilvusContextHolder.clearDatabase();
            }
        }
    }

    /**
     * 在指定的逻辑数据库上下文中执行无返回值闭包任务
     *
     * @param databaseName 目标数据库名称
     * @param action       待执行闭包动作
     */
    public void useDatabase(String databaseName, Runnable action) {
        Assert.notNull(action, "action must not be null");
        useDatabase(databaseName, () -> {
            action.run();
            return null;
        });
    }

    // =========================================================================
    // 通用底层执行器
    // =========================================================================

    /**
     * 原生客户端安全回调执行器，统一由 MilvusExceptionTranslator 提供因果异常保护
     *
     * @param operation 操作语义描述
     * @param action    执行动作
     * @param <T>       返回值泛型
     * @return 执行结果
     */
    public <T> T execute(String operation, Function<MilvusClientV2, T> action) {
        Objects.requireNonNull(action, "Action must not be null");
        try {
            return action.apply(client);
        } catch (Throwable t) {
            throw MilvusExceptionTranslator.translate(operation, t);
        }
    }
}
