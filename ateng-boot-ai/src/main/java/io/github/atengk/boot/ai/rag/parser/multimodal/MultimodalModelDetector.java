package io.github.atengk.boot.ai.rag.parser.multimodal;

import io.github.atengk.boot.ai.properties.AiModelConfig;
import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

/**
 * 视觉多模态大模型能力探测器 (MultimodalModelDetector)
 * <p>
 * 支持基于显式配置开关 ({@code multimodal: true/false}) 与主流视觉多模态大模型家族指纹特征
 * （如 {@code *-vl*}, {@code *vision*}, {@code gpt-4o*}, {@code gemini*}, {@code qwen-vl*} 等）
 * 自动判定模型是否具备图片输入与视觉摘要分析能力，主动防御纯文本模型调用图片触发 HTTP 400 失败。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public final class MultimodalModelDetector {

    /**
     * 主流多模态视觉大模型家族名称特征正则表达式
     */
    private static final Pattern MULTIMODAL_MODEL_PATTERN = Pattern.compile(
            "(?i).*(?:-vl|-vision|vision-|gpt-4o|gemini|qwen.*-vl|deepseek-vl|glm-4v|claude-3|llava|internvl|pixtral|cogvlm|minicpm-v).*"
    );

    private MultimodalModelDetector() {
    }

    /**
     * 判断指定的大模型配置是否具备多模态视觉能力
     *
     * @param config 模型配置对象 (可选)
     * @return true 表示具备多模态能力
     */
    public static boolean isMultimodal(AiModelConfig config) {
        if (config == null) {
            return false;
        }
        if (config.getMultimodal() != null) {
            return config.getMultimodal();
        }
        return isMultimodal(config.getModelName());
    }

    /**
     * 根据模型标识名称判断是否具备多模态视觉能力
     *
     * @param modelName 模型标识名称 (例如 qwen-vl-max, gpt-4o, deepseek-chat 等)
     * @return true 表示模型名称符合多模态特征
     */
    public static boolean isMultimodal(String modelName) {
        if (!StringUtils.hasText(modelName)) {
            return false;
        }
        return MULTIMODAL_MODEL_PATTERN.matcher(modelName.trim()).matches();
    }

    /**
     * 结合模型配置与显式模型名称双重判定
     *
     * @param config    模型配置对象 (可选，优先采用其显式 multimodal 开关)
     * @param modelName 模型标识名称 (可选)
     * @return true 表示具备多模态视觉能力
     */
    public static boolean isMultimodal(AiModelConfig config, String modelName) {
        if (config != null && config.getMultimodal() != null) {
            return config.getMultimodal();
        }
        String effectiveName = StringUtils.hasText(modelName)
                ? modelName
                : (config != null ? config.getModelName() : null);
        return isMultimodal(effectiveName);
    }
}
