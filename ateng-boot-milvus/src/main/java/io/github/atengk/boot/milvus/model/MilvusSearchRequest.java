package io.github.atengk.boot.milvus.model;

import io.github.atengk.boot.milvus.properties.MilvusConsistencyLevel;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.request.data.SparseFloatVec;
import lombok.Data;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.Serializable;
import java.util.*;

/**
 * 单向量高级 ANN 相似度检索链式 Fluent 请求模型
 *
 * 聚合目标向量通道、标量过滤、GroupBy 去重聚合、Radius 与 RangeFilter 范围置信度阈值过滤、
 * 浅分页 Offset、专属检索超参数与多租户安全上下文。
 *
 * @param <T> 实体类型泛型
 * @author Ateng
 * @since 2026-11-04
 */
@Data
public class MilvusSearchRequest<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标逻辑数据库名称 (可选，优先于全局默认配置)
     */
    private String databaseName;

    /**
     * 目标集合名称
     */
    private String collectionName;

    /**
     * 指定检索的物理分区列表 (可选)
     */
    private List<String> partitionNames;

    /**
     * 目标向量列字段名称 (可选，实体驱动时自动推导)
     */
    private String annsField;

    /**
     * 查询向量 (稠密 FloatVec 或稀疏 SparseFloatVec)
     */
    private BaseVector vector;

    /**
     * 召回数量上限 (TopK)
     */
    private int topK = 10;

    /**
     * 浅分页偏移量 (默认 0)
     */
    private long offset = 0;

    /**
     * 标量过滤表达式 (可选)
     */
    private String expr;

    /**
     * 出参字段列表 (为空默认推导实体全部字段或 "*")
     */
    private List<String> outputFields;

    /**
     * 检索去重聚合字段名称 (如 "document_id"，防止单文档切片垄断 TopK)
     */
    private String groupByFieldName;

    /**
     * 范围检索半径/相似度阈值 (可选)
     */
    private Double radius;

    /**
     * 范围检索下限/上限过滤值 (可选)
     */
    private Double rangeFilter;

    /**
     * 专属检索超参数字典 (如 {"nprobe": 10})
     */
    private Map<String, Object> searchParams = new HashMap<>();

    /**
     * 检索读写一致性级别 (可选，默认继承全局配置)
     */
    private MilvusConsistencyLevel consistencyLevel;

    /**
     * 小数舍入位数 (可选)
     */
    private int roundDecimal = -1;

    /**
     * 是否显式忽略多租户隔离过滤
     */
    private boolean ignoreTenant = false;

    /**
     * 目标业务实体类型
     */
    private Class<T> entityClass;

    /**
     * 显式指定的租户字段名称 (可选，无实体元数据时生效)
     */
    private String tenantFieldName;

    public static <T> MilvusSearchRequest<T> builder() {
        return new MilvusSearchRequest<>();
    }

    public MilvusSearchRequest<T> build() {
        return this;
    }

    /**
     * 基于实体类创建单向量检索请求 (自动推导集合名称与主向量列)
     *
     * @param entityClass 实体类
     * @param <T>         实体泛型
     * @return 请求对象
     */
    public static <T> MilvusSearchRequest<T> of(Class<T> entityClass) {
        Assert.notNull(entityClass, "entityClass must not be null");
        MilvusSearchRequest<T> req = new MilvusSearchRequest<>();
        req.setEntityClass(entityClass);
        if (!Map.class.isAssignableFrom(entityClass)) {
            MilvusEntityMetadata metadata = MilvusEntityMetadata.of(entityClass);
            req.setCollectionName(metadata.getCollectionName());
            if (metadata.getVectorField() != null) {
                req.setAnnsField(metadata.getVectorField().getFieldName());
            }
        }
        return req;
    }

    /**
     * 基于集合名称创建单向量检索请求
     *
     * @param collectionName 集合名称
     * @param <T>            实体泛型
     * @return 请求对象
     */
    public static <T> MilvusSearchRequest<T> of(String collectionName) {
        Assert.hasText(collectionName, "collectionName must not be empty");
        MilvusSearchRequest<T> req = new MilvusSearchRequest<>();
        req.setCollectionName(collectionName.trim());
        return req;
    }

    public MilvusSearchRequest<T> collectionName(String collectionName) {
        this.collectionName = collectionName;
        return this;
    }

    public MilvusSearchRequest<T> annsField(String annsField) {
        this.annsField = annsField;
        return this;
    }

    public MilvusSearchRequest<T> vectorFieldName(String vectorFieldName) {
        return annsField(vectorFieldName);
    }

    public MilvusSearchRequest<T> vector(List<Float> vector) {
        Assert.notNull(vector, "vector must not be null");
        this.vector = new FloatVec(vector);
        return this;
    }

    public MilvusSearchRequest<T> vector(float[] vector) {
        Assert.notNull(vector, "vector must not be null");
        List<Float> list = new ArrayList<>(vector.length);
        for (float v : vector) {
            list.add(v);
        }
        this.vector = new FloatVec(list);
        return this;
    }

    public MilvusSearchRequest<T> vector(BaseVector vector) {
        this.vector = vector;
        return this;
    }

    public MilvusSearchRequest<T> sparseVector(SortedMap<Long, Float> sparseVector) {
        Assert.notNull(sparseVector, "sparseVector must not be null");
        this.vector = new SparseFloatVec(sparseVector);
        return this;
    }

    @SuppressWarnings("unchecked")
    public MilvusSearchRequest<T> sparseVector(Map<Long, Float> sparseVector) {
        Assert.notNull(sparseVector, "sparseVector must not be null");
        SortedMap<Long, Float> sorted = (sparseVector instanceof SortedMap)
                ? (SortedMap<Long, Float>) sparseVector
                : new TreeMap<>(sparseVector);
        this.vector = new SparseFloatVec(sorted);
        return this;
    }

    public MilvusSearchRequest<T> topK(int topK) {
        this.topK = topK;
        return this;
    }

    public MilvusSearchRequest<T> offset(long offset) {
        this.offset = offset;
        return this;
    }

    public MilvusSearchRequest<T> expr(String expr) {
        this.expr = expr;
        return this;
    }

    public MilvusSearchRequest<T> outputFields(List<String> outputFields) {
        this.outputFields = outputFields;
        return this;
    }

    public MilvusSearchRequest<T> outputFields(String... outputFields) {
        if (outputFields != null) {
            this.outputFields = new ArrayList<>(Arrays.asList(outputFields));
        }
        return this;
    }

    public MilvusSearchRequest<T> groupBy(String groupByFieldName) {
        this.groupByFieldName = groupByFieldName;
        return this;
    }

    public MilvusSearchRequest<T> groupByFieldName(String groupByFieldName) {
        this.groupByFieldName = groupByFieldName;
        return this;
    }

    public MilvusSearchRequest<T> radius(Double radius) {
        this.radius = radius;
        return this;
    }

    public MilvusSearchRequest<T> radius(Number radius) {
        this.radius = radius != null ? radius.doubleValue() : null;
        return this;
    }

    public MilvusSearchRequest<T> rangeFilter(Double rangeFilter) {
        this.rangeFilter = rangeFilter;
        return this;
    }

    public MilvusSearchRequest<T> rangeFilter(Number rangeFilter) {
        this.rangeFilter = rangeFilter != null ? rangeFilter.doubleValue() : null;
        return this;
    }

    public MilvusSearchRequest<T> range(Double radius, Double rangeFilter) {
        this.radius = radius;
        this.rangeFilter = rangeFilter;
        return this;
    }

    public MilvusSearchRequest<T> searchParam(String key, Object value) {
        Assert.hasText(key, "key must not be empty");
        if (this.searchParams == null) {
            this.searchParams = new HashMap<>();
        }
        this.searchParams.put(key.trim(), value);
        return this;
    }

    public MilvusSearchRequest<T> param(String key, Object value) {
        return searchParam(key, value);
    }

    public MilvusSearchRequest<T> searchParams(Map<String, Object> params) {
        if (params != null) {
            if (this.searchParams == null) {
                this.searchParams = new HashMap<>();
            }
            this.searchParams.putAll(params);
        }
        return this;
    }

    public MilvusSearchRequest<T> params(Map<String, Object> params) {
        return searchParams(params);
    }

    public MilvusSearchRequest<T> consistencyLevel(MilvusConsistencyLevel consistencyLevel) {
        this.consistencyLevel = consistencyLevel;
        return this;
    }

    public MilvusSearchRequest<T> roundDecimal(int roundDecimal) {
        this.roundDecimal = roundDecimal;
        return this;
    }

    public MilvusSearchRequest<T> ignoreTenant(boolean ignoreTenant) {
        this.ignoreTenant = ignoreTenant;
        return this;
    }

    public MilvusSearchRequest<T> databaseName(String databaseName) {
        this.databaseName = databaseName;
        return this;
    }

    public MilvusSearchRequest<T> partitionNames(List<String> partitionNames) {
        this.partitionNames = partitionNames;
        return this;
    }

    public MilvusSearchRequest<T> partitionNames(String... partitionNames) {
        if (partitionNames != null) {
            this.partitionNames = new ArrayList<>(Arrays.asList(partitionNames));
        }
        return this;
    }

    public MilvusSearchRequest<T> entityClass(Class<T> entityClass) {
        this.entityClass = entityClass;
        return this;
    }

    public MilvusSearchRequest<T> tenantFieldName(String tenantFieldName) {
        this.tenantFieldName = tenantFieldName;
        return this;
    }

    /**
     * 构建并返回聚合了 radius、range_filter 与专属 searchParams 的生效检索参数字典
     *
     * @return 完整的生效检索参数字典 (若无参数返回空 Map，非 null)
     */
    public Map<String, Object> buildEffectiveSearchParams() {
        Map<String, Object> effectiveParams = new HashMap<>();
        if (this.searchParams != null && !this.searchParams.isEmpty()) {
            effectiveParams.putAll(this.searchParams);
        }
        if (this.radius != null) {
            effectiveParams.put("radius", this.radius);
        }
        if (this.rangeFilter != null) {
            effectiveParams.put("range_filter", this.rangeFilter);
        }
        return effectiveParams;
    }

    /**
     * 前置防卫校验
     */
    public void validate() {
        String effectiveCollection = collectionName;
        if (!StringUtils.hasText(effectiveCollection) && entityClass != null && !Map.class.isAssignableFrom(entityClass)) {
            effectiveCollection = MilvusEntityMetadata.of(entityClass).getCollectionName();
        }
        if (!StringUtils.hasText(effectiveCollection)) {
            throw new IllegalArgumentException("collectionName must not be empty");
        }
        Assert.notNull(vector, "vector must not be null");
        Assert.isTrue(topK > 0, "topK must be positive");
        Assert.isTrue(offset >= 0, "offset must not be negative");
        Assert.isTrue(offset + topK <= 16384, "offset + topK must not exceed 16384 (Milvus pagination limit)");

        if (rangeFilter != null) {
            Assert.notNull(radius, "radius must not be null when rangeFilter is specified");
        }
        if (radius != null) {
            Assert.isTrue(!Double.isNaN(radius) && !Double.isInfinite(radius), "radius must be a finite number");
            if (rangeFilter != null) {
                Assert.isTrue(!Double.isNaN(rangeFilter) && !Double.isInfinite(rangeFilter), "rangeFilter must be a finite number");
                Assert.isTrue(!radius.equals(rangeFilter), "radius and rangeFilter must not be equal");
            }
        }
    }
}
