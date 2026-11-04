package io.github.atengk.boot.milvus.template;

import io.github.atengk.boot.milvus.exception.MilvusExceptionTranslator;
import io.github.atengk.boot.milvus.model.MilvusEntityMetadata;
import io.github.atengk.boot.milvus.properties.AtengMilvusProperties;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Milvus 向量数据库核心操作门面 (三层立体分工与双核穿透)
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Slf4j
public class MilvusTemplate {

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
        Assert.hasText(collectionName, "collectionName must not be empty");
        Assert.isTrue(dimension > 0, "dimension must be positive");
        CreateCollectionReq req = CreateCollectionReq.builder()
                .collectionName(collectionName)
                .dimension(dimension)
                .build();
        createCollection(req);
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

        // 2. 创建向量索引
        MilvusEntityMetadata.FieldMetadata vectorField = metadata.getVectorField();
        IndexParam.MetricType metricType = vectorField.getMetricType() != null ? vectorField.getMetricType() : IndexParam.MetricType.COSINE;
        IndexParam.IndexType indexType = vectorField.getIndexType() != null ? vectorField.getIndexType() : IndexParam.IndexType.AUTOINDEX;

        List<String> indexes = listIndexes(collectionName);
        if (indexes.isEmpty()) {
            log.info("一键建表: 集合 [{}] 向量列 [{}] 正在创建索引 [type={}, metric={}]",
                    collectionName, vectorField.getFieldName(), indexType, metricType);
            createIndex(collectionName, vectorField.getFieldName(), metricType, indexType);
        }

        // 3. 预加载集合到内存
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

        // 1. 若不存在则创建集合
        if (!hasCollection(collectionName)) {
            log.info("一键建表: 集合 [{}] 不存在，正在极简创建 (dim={})", collectionName, dimension);
            createCollection(collectionName, dimension);
        }

        // 2. 建立索引
        List<String> indexes = listIndexes(collectionName);
        if (indexes.isEmpty()) {
            log.info("一键建表: 集合 [{}] 正在为字段 [{}] 创建索引", collectionName, vectorFieldName);
            createIndex(collectionName, vectorFieldName, metricType, indexType);
        }

        // 3. 预加载集合
        if (!isCollectionLoaded(collectionName)) {
            log.info("一键建表: 正在加载集合 [{}] 至内存", collectionName);
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
