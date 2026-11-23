package io.github.atengk.boot.milvus.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.atengk.boot.milvus.properties.MilvusConsistencyLevel;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.ranker.BaseRanker;
import io.milvus.v2.service.vector.request.ranker.WeightedRanker;
import lombok.Data;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 多向量混合检索链式 Fluent 请求模型
 *
 * @param <T> 实体类型泛型
 * @author Ateng
 * @since 2026-11-04
 */
@Data
public class MilvusHybridSearchRequest<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标逻辑数据库名称 (可选，优先于默认配置)
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
     * 多路向量子检索列表 (稠密/稀疏)
     */
    private List<AnnSearch> searchRequests = new ArrayList<>();

    /**
     * 召回重排融合算法 (默认 RRF k=60)
     */
    private BaseRanker ranker = ReRanker.rrf();

    /**
     * 最终全局召回上限 TopK
     */
    private int topK = 10;

    /**
     * 出参字段列表 (默认全字段 "*")
     */
    private List<String> outputFields;

    /**
     * 检索一致性级别 (可选，默认继承全局配置)
     */
    private MilvusConsistencyLevel consistencyLevel;

    /**
     * 小数舍入位数 (可选)
     */
    private int roundDecimal = -1;

    /**
     * 目标业务实体类型
     */
    private Class<T> entityClass;

    /**
     * 是否显式忽略多租户隔离过滤
     */
    private boolean ignoreTenant = false;

    public static <T> MilvusHybridSearchRequest<T> builder() {
        return new MilvusHybridSearchRequest<>();
    }

    /**
     * 基于实体类创建混合检索请求 (自动推导集合名称)
     *
     * @param entityClass 实体类
     * @param <T>         实体泛型
     * @return 请求对象
     */
    public static <T> MilvusHybridSearchRequest<T> of(Class<T> entityClass) {
        MilvusHybridSearchRequest<T> req = new MilvusHybridSearchRequest<>();
        req.setEntityClass(entityClass);
        if (entityClass != null && !Map.class.isAssignableFrom(entityClass)) {
            MilvusEntityMetadata metadata = MilvusEntityMetadata.of(entityClass);
            req.setCollectionName(metadata.getCollectionName());
        }
        return req;
    }

    /**
     * 基于集合名创建字典式混合检索请求
     *
     * @param collectionName 集合名称
     * @return 请求对象
     */
    public static MilvusHybridSearchRequest<Map<String, Object>> of(String collectionName) {
        MilvusHybridSearchRequest<Map<String, Object>> req = new MilvusHybridSearchRequest<>();
        req.setCollectionName(collectionName);
        return req;
    }

    public MilvusHybridSearchRequest<T> collectionName(String collectionName) {
        this.collectionName = collectionName;
        return this;
    }

    public MilvusHybridSearchRequest<T> databaseName(String databaseName) {
        this.databaseName = databaseName;
        return this;
    }

    public MilvusHybridSearchRequest<T> partitionNames(List<String> partitionNames) {
        this.partitionNames = partitionNames;
        return this;
    }

    public MilvusHybridSearchRequest<T> partitionNames(String... partitionNames) {
        this.partitionNames = Arrays.asList(partitionNames);
        return this;
    }

    public MilvusHybridSearchRequest<T> addSearch(AnnSearch annSearch) {
        if (annSearch != null) {
            this.searchRequests.add(annSearch);
        }
        return this;
    }

    public MilvusHybridSearchRequest<T> searches(List<AnnSearch> searches) {
        if (searches != null) {
            this.searchRequests = new ArrayList<>(searches);
        }
        return this;
    }

    public MilvusHybridSearchRequest<T> searches(AnnSearch... searches) {
        if (searches != null) {
            this.searchRequests = new ArrayList<>(Arrays.asList(searches));
        }
        return this;
    }

    public MilvusHybridSearchRequest<T> ranker(BaseRanker ranker) {
        this.ranker = ranker;
        return this;
    }

    public MilvusHybridSearchRequest<T> rrf(int k) {
        this.ranker = ReRanker.rrf(k);
        return this;
    }

    public MilvusHybridSearchRequest<T> rrf() {
        this.ranker = ReRanker.rrf();
        return this;
    }

    public MilvusHybridSearchRequest<T> weighted(List<Float> weights) {
        this.ranker = ReRanker.weighted(weights);
        return this;
    }

    public MilvusHybridSearchRequest<T> weighted(Float... weights) {
        this.ranker = ReRanker.weighted(weights);
        return this;
    }

    public MilvusHybridSearchRequest<T> topK(int topK) {
        this.topK = topK;
        return this;
    }

    public MilvusHybridSearchRequest<T> outputFields(List<String> outputFields) {
        this.outputFields = outputFields;
        return this;
    }

    public MilvusHybridSearchRequest<T> outputFields(String... fields) {
        this.outputFields = Arrays.asList(fields);
        return this;
    }

    public MilvusHybridSearchRequest<T> consistencyLevel(MilvusConsistencyLevel consistencyLevel) {
        this.consistencyLevel = consistencyLevel;
        return this;
    }

    public MilvusHybridSearchRequest<T> roundDecimal(int roundDecimal) {
        this.roundDecimal = roundDecimal;
        return this;
    }

    public MilvusHybridSearchRequest<T> entityClass(Class<T> entityClass) {
        this.entityClass = entityClass;
        return this;
    }

    public MilvusHybridSearchRequest<T> ignoreTenant(boolean ignoreTenant) {
        this.ignoreTenant = ignoreTenant;
        return this;
    }

    /**
     * 解析当前生效的目标集合名称（优先取显式 collectionName，否则自非 Map 实体元数据推导）
     *
     * @return 集合名称，无法推导时返回 null
     */
    public String resolveEffectiveCollectionName() {
        if (StringUtils.hasText(collectionName)) {
            return collectionName.trim();
        }
        if (entityClass != null && !Map.class.isAssignableFrom(entityClass)) {
            return MilvusEntityMetadata.of(entityClass).getCollectionName();
        }
        return null;
    }

    /**
     * 混合检索请求前置防卫校验 (Fail-Fast 拦截非法集合、空检索通道、空向量与权重路数不匹配)
     */
    public void validate() {
        String effectiveCollection = resolveEffectiveCollectionName();
        if (!StringUtils.hasText(effectiveCollection)) {
            throw new IllegalArgumentException("collectionName must not be empty");
        }
        Assert.notEmpty(searchRequests, "searchRequests must not be empty");
        Assert.isTrue(topK > 0, "topK must be positive");

        for (int i = 0; i < searchRequests.size(); i++) {
            AnnSearch annSearch = searchRequests.get(i);
            Assert.notNull(annSearch, "searchRequests[" + i + "] must not be null");
            Assert.hasText(annSearch.getVectorFieldName(),
                    "searchRequests[" + i + "].vectorFieldName must not be empty");
            BaseVector vector = annSearch.getVector();
            Assert.notNull(vector, "searchRequests[" + i + "].vector must not be null");
            Object vecData = vector.getData();
            Assert.notNull(vecData, "searchRequests[" + i + "].vector data must not be null");
            if (vecData instanceof Collection<?> col) {
                Assert.notEmpty(col, "searchRequests[" + i + "].vector data must not be empty");
            } else if (vecData instanceof Map<?, ?> map) {
                Assert.notEmpty(map, "searchRequests[" + i + "].vector data must not be empty");
            } else if (vecData instanceof ByteBuffer buf) {
                Assert.isTrue(buf.hasRemaining(), "searchRequests[" + i + "].vector data must not be empty");
            }
            Assert.isTrue(annSearch.getTopK() > 0, "searchRequests[" + i + "].topK must be positive");
        }

        if (ranker instanceof WeightedRanker weightedRanker) {
            int weightCount = extractWeightedRankerSize(weightedRanker);
            if (weightCount != searchRequests.size()) {
                throw new IllegalArgumentException(String.format(
                        "WeightedRanker weights size (%d) must match searchRequests size (%d)",
                        weightCount, searchRequests.size()));
            }
        }
    }

    private static int extractWeightedRankerSize(WeightedRanker weightedRanker) {
        try {
            Map<String, String> props = weightedRanker.getProperties();
            String paramsJson = props != null ? props.get("params") : null;
            if (!StringUtils.hasText(paramsJson)) {
                return 0;
            }
            JsonObject paramsObj = JsonParser.parseString(paramsJson).getAsJsonObject();
            JsonArray weightsArray = paramsObj.has("weights") && paramsObj.get("weights").isJsonArray()
                    ? paramsObj.getAsJsonArray("weights")
                    : null;
            return weightsArray != null ? weightsArray.size() : 0;
        } catch (Exception ex) {
            throw new IllegalArgumentException("WeightedRanker weights must not be null or invalid", ex);
        }
    }
}
