package io.github.atengk.boot.ai.registry;

import dev.langchain4j.model.embedding.EmbeddingModel;
import io.github.atengk.boot.ai.context.AiContextHolder;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 向量化模型多命名统一注册中心 (EmbeddingModelRegistry)
 * <p>
 * 纳管多个不同厂商、不同维度与不同区域的 {@link EmbeddingModel} 单例实例，
 * 支持按命名别名获取、注册与枚举探测，协同 {@link AiContextHolder} 实现线程级动态切源。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public class EmbeddingModelRegistry {

    /**
     * 默认模型路由键
     */
    public static final String DEFAULT_MODEL_NAME = "default";

    private final Map<String, EmbeddingModel> models = new ConcurrentHashMap<>();

    /**
     * 注册指定名称的 EmbeddingModel 向量化模型
     *
     * @param name  模型标识名称或别名
     * @param model 向量化模型实例
     */
    public void register(String name, EmbeddingModel model) {
        if (!StringUtils.hasText(name)) {
            throw new AiException(AiResultCode.AI_CONFIG_ERROR, "注册 Embedding 模型名称不能为空");
        }
        if (model != null) {
            models.put(name.trim(), model);
        }
    }

    /**
     * 注册主默认向量化模型及可选别名
     *
     * @param model 向量化模型实例
     * @param alias 模型自定义别名 (如 text-embedding-3-small)
     */
    public void registerDefault(EmbeddingModel model, String alias) {
        register(DEFAULT_MODEL_NAME, model);
        if (StringUtils.hasText(alias) && !DEFAULT_MODEL_NAME.equalsIgnoreCase(alias.trim())) {
            register(alias.trim(), model);
        }
    }

    /**
     * 获取指定名称的向量化模型
     *
     * @param name 模型标识名称
     * @return EmbeddingModel 实例，若未注册返回 null
     */
    public EmbeddingModel getModel(String name) {
        if (!StringUtils.hasText(name)) {
            return null;
        }
        return models.get(name.trim());
    }

    /**
     * 获取全局默认的向量化模型
     *
     * @return 默认 EmbeddingModel 实例
     */
    public EmbeddingModel getDefaultModel() {
        return models.get(DEFAULT_MODEL_NAME);
    }

    /**
     * 获取当前上下文活跃的向量化模型
     * 若当前线程通过 {@link AiContextHolder#setEmbeddingModelName(String)} 指定了活跃模型，优先返回该模型；
     * 否则平滑回退返回默认模型。
     *
     * @return 当前生效的 EmbeddingModel 实例
     */
    public EmbeddingModel getEffectiveModel() {
        String activeModel = AiContextHolder.getEmbeddingModelName();
        if (StringUtils.hasText(activeModel)) {
            EmbeddingModel model = getModel(activeModel);
            if (model != null) {
                return model;
            }
        }
        return getDefaultModel();
    }

    /**
     * 判断是否已注册指定名称的向量化模型
     *
     * @param name 模型标识名称
     * @return true 若已注册，false 否则
     */
    public boolean hasModel(String name) {
        if (!StringUtils.hasText(name)) {
            return false;
        }
        return models.containsKey(name.trim());
    }

    /**
     * 获取已注册的所有向量化模型名称只读集合
     *
     * @return 只读 Set 集合
     */
    public Set<String> listModels() {
        return Collections.unmodifiableSet(models.keySet());
    }

    /**
     * 注销指定名称的向量化模型 (对称注销)
     *
     * @param name 模型标识名称
     * @return 被注销的 EmbeddingModel 实例，若不存在返回 null
     */
    public EmbeddingModel unregister(String name) {
        if (!StringUtils.hasText(name)) {
            return null;
        }
        return models.remove(name.trim());
    }

    /**
     * 移除指定名称的向量化模型 (别名映射，保障对称完备性)
     *
     * @param name 模型标识名称
     * @return 被移除的 EmbeddingModel 实例，若不存在返回 null
     */
    public EmbeddingModel remove(String name) {
        return unregister(name);
    }

    /**
     * 获取已注册模型数量
     *
     * @return 模型实例总数
     */
    public int size() {
        return models.size();
    }

    /**
     * 清空所有已注册的模型实例
     */
    public void clear() {
        models.clear();
    }
}
