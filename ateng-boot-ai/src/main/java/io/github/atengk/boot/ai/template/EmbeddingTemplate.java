package io.github.atengk.boot.ai.template;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import io.github.atengk.boot.ai.context.AiContextHolder;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiExceptionTranslator;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.properties.AiModelConfig;
import io.github.atengk.boot.ai.properties.AtengAiProperties;
import io.github.atengk.boot.ai.properties.AtengAiRagProperties;
import io.github.atengk.boot.ai.registry.EmbeddingModelRegistry;
import io.github.atengk.boot.core.util.RetryUtil;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 统一向量化嵌入门面 (EmbeddingTemplate)
 * <p>
 * 封装底层 {@link EmbeddingModel} 与 {@link EmbeddingModelRegistry}，提供单文本、多文本与 {@link TextSegment} 的向量化计算接口；
 * 支持线程级动态切源 ({@link #useModel(String)})、指定模型计算、自动绑定默认向量存储库与完备对称运维 ({@code ingest} / {@code remove})；
 * 内部统一挂载 {@link RetryUtil} 实施带 Jitter 的指数退避重试治理，并将底层网络或通信异常受控转译为 {@link io.github.atengk.boot.ai.exception.AiException}。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-04
 */
public class EmbeddingTemplate {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingModelRegistry embeddingModelRegistry;
    private final ObjectProvider<EmbeddingStore<TextSegment>> defaultStoreProvider;
    private final EmbeddingStore<TextSegment> defaultStore;
    private final RetryUtil.RetryConfig retryConfig;

    /**
     * 使用默认重试策略构建向量化门面
     *
     * @param embeddingModel 底层向量化模型实例
     */
    public EmbeddingTemplate(EmbeddingModel embeddingModel) {
        this(embeddingModel, null, null, null, null, null);
    }

    /**
     * 基于全局 AI 根配置属性构建向量化门面
     *
     * @param embeddingModel 底层向量化模型实例
     * @param properties     AI 根配置属性
     */
    public EmbeddingTemplate(EmbeddingModel embeddingModel, AtengAiProperties properties) {
        this(embeddingModel, null, null, null, properties != null ? properties.getRag() : null, properties != null ? properties.getModel() : null);
    }

    /**
     * 基于专属 RAG 配置与全局主模型配置构建向量化门面
     *
     * @param embeddingModel 底层向量化模型实例
     * @param ragProperties  RAG 专属配置属性
     * @param rootModelConfig 全局主模型配置
     */
    public EmbeddingTemplate(EmbeddingModel embeddingModel, AtengAiRagProperties ragProperties, AiModelConfig rootModelConfig) {
        this(embeddingModel, null, null, null, ragProperties, rootModelConfig);
    }

    /**
     * 基于多模型注册中心与向量库提供者构建完整向量化门面
     *
     * @param embeddingModel         底层主向量化模型实例 (可选)
     * @param embeddingModelRegistry 多向量化模型统一注册中心 (可选)
     * @param defaultStoreProvider   默认向量存储库提供者 (可选)
     * @param properties             AI 根配置属性 (可选)
     */
    public EmbeddingTemplate(EmbeddingModel embeddingModel,
                             EmbeddingModelRegistry embeddingModelRegistry,
                             ObjectProvider<EmbeddingStore<TextSegment>> defaultStoreProvider,
                             AtengAiProperties properties) {
        this(embeddingModel, embeddingModelRegistry, defaultStoreProvider, null,
                properties != null ? properties.getRag() : null,
                properties != null ? properties.getModel() : null);
    }

    /**
     * 基于显式默认向量库实例构建向量化门面
     *
     * @param embeddingModel         底层主向量化模型实例 (可选)
     * @param embeddingModelRegistry 多向量化模型统一注册中心 (可选)
     * @param defaultStore           默认向量存储库实例 (可选)
     * @param properties             AI 根配置属性 (可选)
     */
    public EmbeddingTemplate(EmbeddingModel embeddingModel,
                             EmbeddingModelRegistry embeddingModelRegistry,
                             EmbeddingStore<TextSegment> defaultStore,
                             AtengAiProperties properties) {
        this(embeddingModel, embeddingModelRegistry, null, defaultStore,
                properties != null ? properties.getRag() : null,
                properties != null ? properties.getModel() : null);
    }

    /**
     * 全参核心构造器
     */
    public EmbeddingTemplate(EmbeddingModel embeddingModel,
                             EmbeddingModelRegistry embeddingModelRegistry,
                             ObjectProvider<EmbeddingStore<TextSegment>> defaultStoreProvider,
                             EmbeddingStore<TextSegment> defaultStore,
                             AtengAiRagProperties ragProperties,
                             AiModelConfig rootModelConfig) {
        this.embeddingModel = embeddingModel;
        this.embeddingModelRegistry = embeddingModelRegistry;
        this.defaultStoreProvider = defaultStoreProvider;
        this.defaultStore = defaultStore;
        this.retryConfig = resolveRetryConfig(ragProperties, rootModelConfig);
    }

    /**
     * 向量模型上下文生命周期作用域接口 (继承 AutoCloseable 但不抛出受检异常)
     */
    @FunctionalInterface
    public interface EmbeddingModelScope extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * 声明式语法糖：临时切换当前线程活跃的向量化模型并返回安全释放句柄
     *
     * <pre>{@code
     * try (EmbeddingTemplate.EmbeddingModelScope ignored = embeddingTemplate.useModel("bge-large")) {
     *     // 此代码块内的向量化计算均自动路由至 bge-large 模型
     *     embeddingTemplate.embed("待向量化文本");
     * }
     * }</pre>
     *
     * @param modelName 目标向量模型名称或别名
     * @return 自动释放句柄
     */
    public EmbeddingModelScope useModel(String modelName) {
        if (!StringUtils.hasText(modelName)) {
            return () -> {};
        }
        AiContextHolder.setEmbeddingModelName(modelName.trim());
        return AiContextHolder::clearEmbedding;
    }

    /**
     * 解析当前线程生效的向量化模型
     * 优先探测线程上下文 {@link AiContextHolder#getEmbeddingModelName()}，其次回退至全局默认模型
     *
     * @return 生效的 EmbeddingModel 实例，若未就绪返回 null
     */
    protected EmbeddingModel resolveCurrentModel() {
        String activeModelName = AiContextHolder.getEmbeddingModelName();
        if (StringUtils.hasText(activeModelName) && this.embeddingModelRegistry != null) {
            EmbeddingModel namedModel = this.embeddingModelRegistry.getModel(activeModelName);
            if (namedModel != null) {
                return namedModel;
            }
        }
        if (this.embeddingModel != null) {
            return this.embeddingModel;
        }
        if (this.embeddingModelRegistry != null) {
            EmbeddingModel defaultModel = this.embeddingModelRegistry.getEffectiveModel();
            if (defaultModel != null) {
                return defaultModel;
            }
        }
        return null;
    }

    /**
     * 判断底层向量化模型是否已配置且就绪
     *
     * @return true 若已就绪，false 为待命状态
     */
    public boolean isAvailable() {
        return resolveCurrentModel() != null;
    }

    private EmbeddingModel ensureModelAvailable() {
        EmbeddingModel model = resolveCurrentModel();
        if (model == null) {
            throw new AiException(AiResultCode.AI_CONFIG_ERROR,
                    "未配置有效的 EmbeddingModel 向量化模型 (缺少 api-key 或未注册自定义模型 Bean)，无法执行向量化计算");
        }
        return model;
    }

    private EmbeddingModel ensureModelAvailable(String modelName) {
        if (!StringUtils.hasText(modelName)) {
            return ensureModelAvailable();
        }
        if (this.embeddingModelRegistry != null) {
            EmbeddingModel namedModel = this.embeddingModelRegistry.getModel(modelName.trim());
            if (namedModel != null) {
                return namedModel;
            }
        }
        throw new AiException(AiResultCode.AI_CONFIG_ERROR,
                "未找到标识为 [" + modelName + "] 的 Embedding 向量化模型，请确认是否已在配置或 EmbeddingModelRegistry 中正确注册");
    }

    /**
     * 解析当前环境下的默认向量存储库实例
     *
     * @return 默认的 EmbeddingStore 实例
     * @throws AiException 若未检测到任何可用的 EmbeddingStore
     */
    public EmbeddingStore<TextSegment> resolveDefaultStore() {
        if (defaultStoreProvider != null) {
            EmbeddingStore<TextSegment> store = defaultStoreProvider.getIfAvailable();
            if (store != null) {
                return store;
            }
        }
        if (this.defaultStore != null) {
            return this.defaultStore;
        }
        throw new AiException(AiResultCode.AI_CONFIG_ERROR,
                "未检测到任何可用的 EmbeddingStore 向量存储库 Bean (如 MilvusEmbeddingStore 或 InMemoryEmbeddingStore)，无法执行默认入库/删除操作");
    }

    /**
     * 单文本向量化计算 (使用当前上下文活跃模型)
     *
     * @param text 待向量化的明文文本
     * @return 包含 Embedding 向量与 Token 消耗的响应结果
     */
    public Response<Embedding> embed(String text) {
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("待向量化文本不能为空");
        }
        EmbeddingModel model = ensureModelAvailable();
        try {
            return RetryUtil.execute(() -> model.embed(text), retryConfig);
        } catch (Throwable t) {
            throw AiExceptionTranslator.translate("单文本向量化计算失败", t);
        }
    }

    /**
     * 批量文本向量化计算 (使用当前上下文活跃模型)
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
     * 单文本片段 (TextSegment) 向量化计算 (使用当前上下文活跃模型)
     *
     * @param segment 待向量化的文本片段实例 (携带元数据)
     * @return 包含 Embedding 向量与 Token 消耗的响应结果
     */
    public Response<Embedding> embed(TextSegment segment) {
        if (segment == null) {
            throw new IllegalArgumentException("TextSegment 文本片段对象不能为空");
        }
        EmbeddingModel model = ensureModelAvailable();
        try {
            return RetryUtil.execute(() -> model.embed(segment), retryConfig);
        } catch (Throwable t) {
            throw AiExceptionTranslator.translate("文本片段向量化计算失败", t);
        }
    }

    /**
     * 批量文本片段 (TextSegment) 向量化计算 (使用当前上下文活跃模型)
     *
     * @param segments 待向量化的文本片段列表
     * @return 包含多个 Embedding 向量的响应结果；入参为空时保底返回空响应
     */
    public Response<List<Embedding>> embedSegments(List<TextSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return Response.from(Collections.emptyList());
        }
        EmbeddingModel model = ensureModelAvailable();
        try {
            return RetryUtil.execute(() -> model.embedAll(segments), retryConfig);
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
        EmbeddingModel model = ensureModelAvailable();
        return model.dimension();
    }

    // ==================== 指定命名模型显式调用 API ====================

    /**
     * 指定命名模型单文本向量化计算
     *
     * @param modelName 命名模型标识或别名
     * @param text      待向量化的明文文本
     * @return 响应结果
     */
    public Response<Embedding> embedWithModel(String modelName, String text) {
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("待向量化文本不能为空");
        }
        EmbeddingModel model = ensureModelAvailable(modelName);
        try {
            return RetryUtil.execute(() -> model.embed(text), retryConfig);
        } catch (Throwable t) {
            throw AiExceptionTranslator.translate("单文本向量化计算失败 [model=" + modelName + "]", t);
        }
    }

    /**
     * 指定命名模型批量文本向量化计算
     *
     * @param modelName 命名模型标识或别名
     * @param texts     待向量化的明文文本列表
     * @return 响应结果
     */
    public Response<List<Embedding>> embedWithModel(String modelName, List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Response.from(Collections.emptyList());
        }
        List<TextSegment> segments = texts.stream()
                .map(t -> StringUtils.hasText(t) ? TextSegment.from(t) : TextSegment.from(""))
                .toList();
        return embedSegmentsWithModel(modelName, segments);
    }

    /**
     * 指定命名模型文本片段向量化计算
     *
     * @param modelName 命名模型标识或别名
     * @param segment   待向量化片段
     * @return 响应结果
     */
    public Response<Embedding> embedWithModel(String modelName, TextSegment segment) {
        if (segment == null) {
            throw new IllegalArgumentException("TextSegment 文本片段对象不能为空");
        }
        EmbeddingModel model = ensureModelAvailable(modelName);
        try {
            return RetryUtil.execute(() -> model.embed(segment), retryConfig);
        } catch (Throwable t) {
            throw AiExceptionTranslator.translate("文本片段向量化计算失败 [model=" + modelName + "]", t);
        }
    }

    /**
     * 指定命名模型批量文本片段向量化计算
     *
     * @param modelName 命名模型标识或别名
     * @param segments  待向量化片段列表
     * @return 响应结果
     */
    public Response<List<Embedding>> embedSegmentsWithModel(String modelName, List<TextSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return Response.from(Collections.emptyList());
        }
        EmbeddingModel model = ensureModelAvailable(modelName);
        try {
            return RetryUtil.execute(() -> model.embedAll(segments), retryConfig);
        } catch (Throwable t) {
            throw AiExceptionTranslator.translate("批量文本片段向量化计算失败 [model=" + modelName + "]", t);
        }
    }

    /**
     * 指定命名模型提取单文本稠密浮点向量 float[]
     *
     * @param modelName 命名模型标识或别名
     * @param text      待向量化文本
     * @return 浮点向量数组
     */
    public float[] embedVectorWithModel(String modelName, String text) {
        Response<Embedding> response = embedWithModel(modelName, text);
        if (response == null || response.content() == null) {
            return new float[0];
        }
        return response.content().vector();
    }

    /**
     * 指定命名模型批量提取稠密浮点向量
     *
     * @param modelName 命名模型标识或别名
     * @param texts     待向量化文本列表
     * @return 浮点向量列表
     */
    public List<float[]> embedVectorsWithModel(String modelName, List<String> texts) {
        Response<List<Embedding>> response = embedWithModel(modelName, texts);
        if (response == null || response.content() == null || response.content().isEmpty()) {
            return Collections.emptyList();
        }
        return response.content().stream()
                .map(Embedding::vector)
                .toList();
    }

    /**
     * 获取指定命名模型的特征向量维度
     *
     * @param modelName 命名模型标识或别名
     * @return 向量维度数
     */
    public int dimensionWithModel(String modelName) {
        EmbeddingModel model = ensureModelAvailable(modelName);
        return model.dimension();
    }

    // ==================== 知识库入库与对称清理 API (支持显式 Store 与默认 Store) ====================

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
     * 将单个文本片段向量化并写入默认向量存储库
     *
     * @param segment 待入库文本片段
     * @return 向量存储分配的唯一主键 ID
     */
    public String ingest(TextSegment segment) {
        return ingest(resolveDefaultStore(), segment);
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
     * 将单个纯文本字符串向量化并写入默认向量存储库
     *
     * @param text 待入库纯文本
     * @return 向量存储分配的唯一主键 ID
     */
    public String ingest(String text) {
        return ingest(resolveDefaultStore(), text);
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
     * 批量将文本片段向量化并写入默认向量存储库
     *
     * @param segments 待入库文本片段列表
     * @return 向量存储分配的主键 ID 列表
     */
    public List<String> ingestAll(List<TextSegment> segments) {
        return ingestAll(resolveDefaultStore(), segments);
    }

    /**
     * 指定命名模型将文本片段向量化并写入指定向量存储库
     *
     * @param modelName 命名模型标识或别名
     * @param store     目标向量数据库
     * @param segment   待存储片段
     * @return 向量存储主键 ID
     */
    public String ingestWithModel(String modelName, EmbeddingStore<TextSegment> store, TextSegment segment) {
        if (store == null) {
            throw new IllegalArgumentException("目标 EmbeddingStore 存储库不能为空");
        }
        if (segment == null) {
            throw new IllegalArgumentException("待存储 TextSegment 不能为空");
        }
        Response<Embedding> response = embedWithModel(modelName, segment);
        return store.add(response.content(), segment);
    }

    /**
     * 指定命名模型将文本片段向量化并写入默认向量存储库
     *
     * @param modelName 命名模型标识或别名
     * @param segment   待存储片段
     * @return 向量存储主键 ID
     */
    public String ingestWithModel(String modelName, TextSegment segment) {
        return ingestWithModel(modelName, resolveDefaultStore(), segment);
    }

    /**
     * 指定命名模型批量将文本片段向量化并写入指定向量存储库
     *
     * @param modelName 命名模型标识或别名
     * @param store     目标向量数据库
     * @param segments  待存储片段列表
     * @return 主键 ID 列表
     */
    public List<String> ingestAllWithModel(String modelName, EmbeddingStore<TextSegment> store, List<TextSegment> segments) {
        if (store == null) {
            throw new IllegalArgumentException("目标 EmbeddingStore 存储库不能为空");
        }
        if (segments == null || segments.isEmpty()) {
            return Collections.emptyList();
        }
        Response<List<Embedding>> response = embedSegmentsWithModel(modelName, segments);
        return store.addAll(response.content(), segments);
    }

    /**
     * 指定命名模型批量将文本片段向量化并写入默认向量存储库
     *
     * @param modelName 命名模型标识或别名
     * @param segments  待存储片段列表
     * @return 主键 ID 列表
     */
    public List<String> ingestAllWithModel(String modelName, List<TextSegment> segments) {
        return ingestAllWithModel(modelName, resolveDefaultStore(), segments);
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
     * 从默认向量存储库移除单条向量记录
     *
     * @param id 待移除的主键 ID
     */
    public void remove(String id) {
        remove(resolveDefaultStore(), id);
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
     * 从默认向量存储库批量移除向量记录
     *
     * @param ids 待移除的主键 ID 集合
     */
    public void removeAll(Collection<String> ids) {
        removeAll(resolveDefaultStore(), ids);
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
     * 排空并清空默认向量存储库中的所有向量记录
     */
    public void removeAll() {
        removeAll(resolveDefaultStore());
    }

    /**
     * 从指定向量存储库 (EmbeddingStore) 依据 Filter 过滤器条件批量移除向量记录 (对称回收)
     *
     * @param store  目标向量数据库或存储实现
     * @param filter 匹配过滤表达式
     */
    public void removeAll(EmbeddingStore<TextSegment> store, Filter filter) {
        if (store == null) {
            throw new IllegalArgumentException("目标 EmbeddingStore 存储库不能为空");
        }
        if (filter == null) {
            return;
        }
        store.removeAll(filter);
    }

    /**
     * 从默认向量存储库依据 Filter 过滤器条件批量移除向量记录
     *
     * @param filter 匹配过滤表达式
     */
    public void removeAll(Filter filter) {
        removeAll(resolveDefaultStore(), filter);
    }

    /**
     * 获取底层包装的当前活跃 EmbeddingModel 原生实例
     *
     * @return EmbeddingModel 实例
     */
    public EmbeddingModel getModel() {
        EmbeddingModel model = resolveCurrentModel();
        return model != null ? model : this.embeddingModel;
    }

    /**
     * 获取底层多模型命名注册中心
     *
     * @return EmbeddingModelRegistry 实例，可能为 null
     */
    public EmbeddingModelRegistry getRegistry() {
        return embeddingModelRegistry;
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
