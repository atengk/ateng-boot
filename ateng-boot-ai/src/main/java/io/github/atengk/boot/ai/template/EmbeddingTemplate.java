package io.github.atengk.boot.ai.template;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiExceptionTranslator;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.properties.AiModelConfig;
import io.github.atengk.boot.ai.properties.AtengAiProperties;
import io.github.atengk.boot.ai.properties.AtengAiRagProperties;
import io.github.atengk.boot.core.util.RetryUtil;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 统一向量化嵌入门面 (EmbeddingTemplate)
 * <p>
 * 封装底层 {@link EmbeddingModel}，提供单文本、多文本与 {@link TextSegment} 的向量化计算接口；
 * 内部统一挂载 {@link RetryUtil} 实施带 Jitter 的指数退避重试治理，并将底层网络或通信异常受控转译为 {@link io.github.atengk.boot.ai.exception.AiException}。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-04
 */
public class EmbeddingTemplate {

    private final EmbeddingModel embeddingModel;
    private final RetryUtil.RetryConfig retryConfig;

    /**
     * 使用默认重试策略构建向量化门面
     *
     * @param embeddingModel 底层向量化模型实例
     */
    public EmbeddingTemplate(EmbeddingModel embeddingModel) {
        this(embeddingModel, buildDefaultRetryConfig());
    }

    /**
     * 基于全局 AI 根配置属性构建向量化门面
     *
     * @param embeddingModel 底层向量化模型实例
     * @param properties     AI 根配置属性
     */
    public EmbeddingTemplate(EmbeddingModel embeddingModel, AtengAiProperties properties) {
        this(embeddingModel, resolveRetryConfig(properties != null ? properties.getRag() : null, properties != null ? properties.getModel() : null));
    }

    /**
     * 基于专属 RAG 配置与全局主模型配置构建向量化门面
     *
     * @param embeddingModel 底层向量化模型实例
     * @param ragProperties  RAG 专属配置属性
     * @param rootModelConfig 全局主模型配置
     */
    public EmbeddingTemplate(EmbeddingModel embeddingModel, AtengAiRagProperties ragProperties, AiModelConfig rootModelConfig) {
        this(embeddingModel, resolveRetryConfig(ragProperties, rootModelConfig));
    }

    private EmbeddingTemplate(EmbeddingModel embeddingModel, RetryUtil.RetryConfig retryConfig) {
        this.embeddingModel = embeddingModel;
        this.retryConfig = retryConfig != null ? retryConfig : buildDefaultRetryConfig();
    }

    /**
     * 判断底层向量化模型是否已配置且就绪
     *
     * @return true 若已就绪，false 为待命状态
     */
    public boolean isAvailable() {
        return this.embeddingModel != null;
    }

    private void ensureModelAvailable() {
        if (this.embeddingModel == null) {
            throw new AiException(AiResultCode.AI_CONFIG_ERROR,
                    "未配置有效的 EmbeddingModel 向量化模型 (缺少 api-key 或未注册自定义模型 Bean)，无法执行向量化计算");
        }
    }

    /**
     * 单文本向量化计算
     *
     * @param text 待向量化的明文文本
     * @return 包含 Embedding 向量与 Token 消耗的响应结果
     */
    public Response<Embedding> embed(String text) {
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("待向量化文本不能为空");
        }
        ensureModelAvailable();
        try {
            return RetryUtil.execute(() -> embeddingModel.embed(text), retryConfig);
        } catch (Throwable t) {
            throw AiExceptionTranslator.translate("单文本向量化计算失败", t);
        }
    }

    /**
     * 批量文本向量化计算
     *
     * @param texts 待向量化的明文文本列表
     * @return 包含多个 Embedding 向量的响应结果；入参为空时保底返回空响应
     */
    public Response<List<Embedding>> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Response.from(Collections.emptyList());
        }
        List<TextSegment> segments = texts.stream()
                .map(t -> StringUtils.hasText(t) ? TextSegment.from(t) : TextSegment.from(""))
                .toList();
        return embedSegments(segments);
    }

    /**
     * 单文本片段 (TextSegment) 向量化计算
     *
     * @param segment 待向量化的文本片段实例 (携带元数据)
     * @return 包含 Embedding 向量与 Token 消耗的响应结果
     */
    public Response<Embedding> embed(TextSegment segment) {
        if (segment == null) {
            throw new IllegalArgumentException("TextSegment 文本片段对象不能为空");
        }
        ensureModelAvailable();
        try {
            return RetryUtil.execute(() -> embeddingModel.embed(segment), retryConfig);
        } catch (Throwable t) {
            throw AiExceptionTranslator.translate("文本片段向量化计算失败", t);
        }
    }

    /**
     * 批量文本片段 (TextSegment) 向量化计算
     *
     * @param segments 待向量化的文本片段列表
     * @return 包含多个 Embedding 向量的响应结果；入参为空时保底返回空响应
     */
    public Response<List<Embedding>> embedSegments(List<TextSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return Response.from(Collections.emptyList());
        }
        ensureModelAvailable();
        try {
            return RetryUtil.execute(() -> embeddingModel.embedAll(segments), retryConfig);
        } catch (Throwable t) {
            throw AiExceptionTranslator.translate("批量文本片段向量化计算失败", t);
        }
    }

    /**
     * 单文本向量化浮点数组便捷提取 (极速返回 raw float[] 向量)
     *
     * @param text 待向量化的明文文本
     * @return 稠密浮点向量数组 float[]
     */
    public float[] embedVector(String text) {
        Response<Embedding> response = embed(text);
        if (response == null || response.content() == null) {
            return new float[0];
        }
        return response.content().vector();
    }

    /**
     * 批量文本向量化浮点数组便捷提取
     *
     * @param texts 待向量化的明文文本列表
     * @return 稠密浮点向量数组列表 List&lt;float[]&gt;
     */
    public List<float[]> embedVectors(List<String> texts) {
        Response<List<Embedding>> response = embed(texts);
        if (response == null || response.content() == null || response.content().isEmpty()) {
            return Collections.emptyList();
        }
        return response.content().stream()
                .map(Embedding::vector)
                .toList();
    }

    /**
     * 获取当前向量化模型的特征向量维度
     *
     * @return 向量维度数
     */
    public int dimension() {
        ensureModelAvailable();
        return embeddingModel.dimension();
    }

    /**
     * 将单个文本片段向量化并写入指定向量存储库 (EmbeddingStore)
     *
     * @param store   目标向量数据库或存储实现
     * @param segment 待入库文本片段
     * @return 向量存储分配的唯一主键 ID
     */
    public String ingest(EmbeddingStore<TextSegment> store, TextSegment segment) {
        if (store == null) {
            throw new IllegalArgumentException("目标 EmbeddingStore 存储库不能为空");
        }
        if (segment == null) {
            throw new IllegalArgumentException("待存储 TextSegment 不能为空");
        }
        Response<Embedding> response = embed(segment);
        return store.add(response.content(), segment);
    }

    /**
     * 将单个纯文本字符串向量化并写入指定向量存储库 (EmbeddingStore)
     *
     * @param store 目标向量数据库或存储实现
     * @param text  待入库纯文本
     * @return 向量存储分配的唯一主键 ID
     */
    public String ingest(EmbeddingStore<TextSegment> store, String text) {
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("待存储文本内容不能为空");
        }
        return ingest(store, TextSegment.from(text));
    }

    /**
     * 批量将文本片段向量化并写入指定向量存储库 (EmbeddingStore)
     *
     * @param store    目标向量数据库或存储实现
     * @param segments 待入库文本片段列表
     * @return 向量存储分配的主键 ID 列表
     */
    public List<String> ingestAll(EmbeddingStore<TextSegment> store, List<TextSegment> segments) {
        if (store == null) {
            throw new IllegalArgumentException("目标 EmbeddingStore 存储库不能为空");
        }
        if (segments == null || segments.isEmpty()) {
            return Collections.emptyList();
        }
        Response<List<Embedding>> response = embedSegments(segments);
        return store.addAll(response.content(), segments);
    }

    /**
     * 从指定向量存储库 (EmbeddingStore) 移除单条向量记录 (对称回收)
     *
     * @param store 目标向量数据库或存储实现
     * @param id    待移除的主键 ID
     */
    public void remove(EmbeddingStore<TextSegment> store, String id) {
        if (store == null) {
            throw new IllegalArgumentException("目标 EmbeddingStore 存储库不能为空");
        }
        if (!StringUtils.hasText(id)) {
            return;
        }
        store.remove(id.trim());
    }

    /**
     * 从指定向量存储库 (EmbeddingStore) 批量移除向量记录 (对称回收)
     *
     * @param store 目标向量数据库或存储实现
     * @param ids   待移除的主键 ID 集合
     */
    public void removeAll(EmbeddingStore<TextSegment> store, Collection<String> ids) {
        if (store == null) {
            throw new IllegalArgumentException("目标 EmbeddingStore 存储库不能为空");
        }
        if (ids == null || ids.isEmpty()) {
            return;
        }
        store.removeAll(ids);
    }

    /**
     * 排空并清空指定向量存储库 (EmbeddingStore) 中的所有向量记录 (对称回收)
     *
     * @param store 目标向量数据库或存储实现
     */
    public void removeAll(EmbeddingStore<TextSegment> store) {
        if (store == null) {
            throw new IllegalArgumentException("目标 EmbeddingStore 存储库不能为空");
        }
        store.removeAll();
    }

    /**
     * 获取底层包装的 EmbeddingModel 原生实例
     *
     * @return EmbeddingModel 实例
     */
    public EmbeddingModel getModel() {
        return embeddingModel;
    }

    private static RetryUtil.RetryConfig buildDefaultRetryConfig() {
        return RetryUtil.RetryConfig.builder()
                .maxAttempts(4) // 默认 3 次重试 (总共 4 次尝试)
                .delay(Duration.ofMillis(500))
                .multiplier(2.0)
                .maxDelay(Duration.ofSeconds(5))
                .jitter(true)
                .retryPredicate(AiExceptionTranslator::isRetryable)
                .build();
    }

    private static RetryUtil.RetryConfig resolveRetryConfig(AtengAiRagProperties ragProperties, AiModelConfig rootModelConfig) {
        if (ragProperties == null && rootModelConfig == null) {
            return buildDefaultRetryConfig();
        }
        AtengAiRagProperties rag = ragProperties != null ? ragProperties : new AtengAiRagProperties();
        AiModelConfig config = rag.resolveEmbeddingConfig(rootModelConfig);

        int maxRetries = config.getMaxRetries() != null ? config.getMaxRetries() : 3;
        Duration initialInterval = config.getRetryInitialInterval() != null ? config.getRetryInitialInterval() : Duration.ofMillis(500);
        Duration maxInterval = config.getRetryMaxInterval() != null ? config.getRetryMaxInterval() : Duration.ofSeconds(5);
        double multiplier = config.getRetryMultiplier() != null ? config.getRetryMultiplier() : 2.0;

        return RetryUtil.RetryConfig.builder()
                .maxAttempts(Math.max(1, maxRetries + 1))
                .delay(initialInterval)
                .multiplier(multiplier)
                .maxDelay(maxInterval)
                .jitter(true)
                .retryPredicate(AiExceptionTranslator::isRetryable)
                .build();
    }
}
