package io.github.atengk.boot.ai.rag.parser.multimodal;

import dev.langchain4j.model.chat.ChatLanguageModel;
import io.github.atengk.boot.ai.factory.OpenAiModelFactory;
import io.github.atengk.boot.ai.properties.AiModelConfig;
import io.github.atengk.boot.ai.properties.AtengAiProperties;
import io.github.atengk.boot.ai.properties.AtengAiRagProperties;
import io.github.atengk.boot.ai.registry.AiModelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/**
 * 视觉多模态大模型智能解析器 (VisionModelResolver)
 * <p>
 * 依托四级级联解析链安全寻址视觉多模态大模型，主动阻断纯文本主模型接收图片触发的 HTTP 400 异常：
 * <ol>
 *     <li><b>Tier 1 显式指定</b>：优先采用 {@code rag.parser.multimodal.model} 或独立配置的 {@code modelConfig}；</li>
 *     <li><b>Tier 2 注册表探测</b>：自动遍历 {@link AiModelRegistry} 中所有已注册的命名模型，自动匹配具备视觉特征的模型；</li>
 *     <li><b>Tier 3 全局主模型</b>：若全局主模型 {@code ateng.boot.ai.model} 具备多模态能力，则安全复用；</li>
 *     <li><b>Tier 4 平滑降级</b>：未检测到任何可用视觉模型时，返回 {@code null} 并优雅降级为图表占位符模式，保证文档解析链路 100% 稳定运行。</li>
 * </ol>
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public class VisionModelResolver {

    private static final Logger log = LoggerFactory.getLogger(VisionModelResolver.class);

    private final AtengAiRagProperties.MultimodalProperties multimodalProperties;
    private final AiModelRegistry aiModelRegistry;
    private final AtengAiProperties atengAiProperties;

    public VisionModelResolver(AtengAiRagProperties.MultimodalProperties multimodalProperties,
                               AiModelRegistry aiModelRegistry,
                               AtengAiProperties atengAiProperties) {
        this.multimodalProperties = multimodalProperties != null
                ? multimodalProperties
                : new AtengAiRagProperties.MultimodalProperties();
        this.aiModelRegistry = aiModelRegistry;
        this.atengAiProperties = atengAiProperties;
    }

    /**
     * 执行四级级联查找并返回可用的视觉大模型实例
     *
     * @return ChatLanguageModel 视觉模型实例；若未命中则返回 null (指示安全降级)
     */
    public ChatLanguageModel resolveVisionModel() {
        // Tier 1: 显式指定
        if (multimodalProperties.getModelConfig() != null && multimodalProperties.getModelConfig().isConfigured()) {
            AiModelConfig cfg = multimodalProperties.getModelConfig();
            log.info("视觉模型解析成功 [Tier 1 显式独立配置: modelName={}, baseUrl={}]",
                    cfg.getModelName(), cfg.getBaseUrl());
            return OpenAiModelFactory.createChatModel(cfg);
        }
        if (StringUtils.hasText(multimodalProperties.getModel()) && aiModelRegistry != null) {
            String target = multimodalProperties.getModel().trim();
            if (aiModelRegistry.hasChatModel(target)) {
                log.info("视觉模型解析成功 [Tier 1 显式命名模型: {}]", target);
                return aiModelRegistry.getChatModel(target);
            }
        }

        // Tier 2: 注册中心自动探测
        if (aiModelRegistry != null && !aiModelRegistry.getChatModelNames().isEmpty()) {
            for (String name : aiModelRegistry.getChatModelNames()) {
                if (MultimodalModelDetector.isMultimodal(name)) {
                    log.info("视觉模型解析成功 [Tier 2 注册中心自动探测命中: {}]", name);
                    return aiModelRegistry.getChatModel(name);
                }
            }
        }

        // Tier 3: 全局主模型
        if (atengAiProperties != null && atengAiProperties.getModel() != null) {
            AiModelConfig rootConfig = atengAiProperties.getModel();
            if (MultimodalModelDetector.isMultimodal(rootConfig, rootConfig.getModelName()) && aiModelRegistry != null) {
                if (aiModelRegistry.hasChatModel(AiModelRegistry.DEFAULT_MODEL_NAME)) {
                    log.info("视觉模型解析成功 [Tier 3 全局主模型多模态复用: {}]", rootConfig.getModelName());
                    return aiModelRegistry.getChatModel(AiModelRegistry.DEFAULT_MODEL_NAME);
                }
            }
        }

        // Tier 4: 平滑降级
        log.warn("当前环境未检测到可用的多模态视觉大模型，文档解析将自动降级为图表占位符模式");
        return null;
    }

    /**
     * 判断当前系统是否具备可用的多模态视觉大模型
     *
     * @return true 表示具备视觉解析能力
     */
    public boolean hasVisionCapability() {
        return resolveVisionModel() != null;
    }
}
