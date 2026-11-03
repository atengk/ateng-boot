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
}
