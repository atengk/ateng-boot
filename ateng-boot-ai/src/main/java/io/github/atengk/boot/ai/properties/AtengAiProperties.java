package io.github.atengk.boot.ai.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 智能体与大模型底座根配置属性
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
@ConfigurationProperties(prefix = "ateng.boot.ai")
public class AtengAiProperties {

    /**
     * 是否启用 AI 智能体与大模型基础设施
     */
    private boolean enabled = true;

    /**
     * 默认全局主模型配置
     */
    @NestedConfigurationProperty
    private AiModelConfig model = new AiModelConfig();

    /**
     * 会话记忆治理强类型配置
     */
    @NestedConfigurationProperty
    private ChatMemoryProperties chatMemory = new ChatMemoryProperties();

    /**
     * 全维链路可观测审计与脱敏配置
     */
    @NestedConfigurationProperty
    private AiAuditProperties audit = new AiAuditProperties();

    /**
     * 命名多模型配置拓扑 (key 为模型标识别名，例如 deepseek-r1, qwen-max, fast-chat 等)
     */
    private Map<String, AiModelConfig> models = new LinkedHashMap<>();

    /**
     * 获取不可变多模型配置映射，防御外部无界篡改
     *
     * @return 只读多模型配置 Map
     */
    public Map<String, AiModelConfig> getModels() {
        if (models == null || models.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(models);
    }

    /**
     * 根据模型标识名称解析对应的模型配置；若未显式配置则平滑回退至默认模型配置
     *
     * @param modelName 模型名称或别名
     * @return 对应的 AiModelConfig 实例，绝不返回 null
     */
    public AiModelConfig resolveConfig(String modelName) {
        if (modelName != null && !modelName.isBlank() && models != null && models.containsKey(modelName.trim())) {
            AiModelConfig config = models.get(modelName.trim());
            if (config != null) {
                return config;
            }
        }
        return model != null ? model : new AiModelConfig();
    }
}
