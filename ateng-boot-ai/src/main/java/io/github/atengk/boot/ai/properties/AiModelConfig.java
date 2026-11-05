package io.github.atengk.boot.ai.properties;

import lombok.Data;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容模型独立配置
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
public class AiModelConfig {

    /**
     * 默认聊天模型标识名称
     */
    public static final String DEFAULT_MODEL_NAME = "gpt-4o";

    /**
     * 服务端基础地址 (例如 https://api.deepseek.com/v1 或 https://dashscope.aliyuncs.com/compatible-mode/v1)
     */
    private String baseUrl = "https://api.openai.com/v1";

    /**
     * API 访问密钥凭据
     */
    private String apiKey;

    /**
     * 模型标识名称 (例如 deepseek-chat, deepseek-reasoner, qwen-plus, gpt-4o 等)
     */
    private String modelName = DEFAULT_MODEL_NAME;

    /**
     * 采样温度 (0.0 ~ 2.0)
     */
    private Double temperature = 0.7;

    /**
     * 核采样阈值 Top-P
     */
    private Double topP;

    /**
     * 最大生成 Token 限制数
     */
    private Integer maxTokens;

    /**
     * 网络连接与读取超时阈值
     */
    private Duration timeout = Duration.ofSeconds(60);

    /**
     * 瞬时失败最大重试次数
     */
    private Integer maxRetries = 3;

    /**
     * 瞬时失败重试初始退避延迟间隔
     */
    private Duration retryInitialInterval = Duration.ofMillis(500);

    /**
     * 瞬时失败重试最大退避延迟间隔
     */
    private Duration retryMaxInterval = Duration.ofSeconds(5);

    /**
     * 瞬时失败重试退避指数倍率
     */
    private Double retryMultiplier = 2.0;

    /**
     * 是否在控制台输出底层 HTTP 请求日志
     */
    private Boolean logRequests = false;

    /**
     * 是否在控制台输出底层 HTTP 响应日志
     */
    private Boolean logResponses = false;

    /**
     * 自定义 HTTP 请求头拓扑
     */
    private Map<String, String> customHeaders = new LinkedHashMap<>();

    /**
     * 大模型响应格式约束 (TEXT: 自由文本, JSON_OBJECT: 标准 JSON 对象, JSON_SCHEMA: 严格 JSON Schema 语法约束解码)
     */
    private AiResponseFormat responseFormat = AiResponseFormat.TEXT;

    /**
     * 故障自动转移备选模型名称序列 (按优先级排序，例如 [backup-a, backup-b])
     */
    private List<String> fallbacks = new ArrayList<>();

    /**
     * 故障转移与重试的总全局时延预算 (Total Deadline Budget，例如 30s)
     * 若未显式配置，则自动采用模型自身的 timeout 乘以候选模型数或采用单次 timeout
     */
    private Duration totalTimeout;

    /**
     * 获取不可变备选模型列表，防御外部篡改
     *
     * @return 只读备选模型名称 List
     */
    public List<String> getFallbacks() {
        if (fallbacks == null || fallbacks.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(fallbacks);
    }

    /**
     * 判断当前模型配置是否具备有效的 API 访问凭据
     *
     * @return 若已配置非空白 API Key 返回 true，否则返回 false
     */
    public boolean isConfigured() {
        return StringUtils.hasText(apiKey);
    }

    /**
     * 获取不可变自定义请求头集合，防御外部污染
     *
     * @return 只读请求头 Map
     */
    public Map<String, String> getCustomHeaders() {
        if (customHeaders == null || customHeaders.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(customHeaders);
    }
}
