package io.github.atengk.boot.ai.guardrail;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 双向内容安全护栏强类型配置属性
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
public class AiGuardrailProperties {

    /**
     * 是否启用双向内容安全护栏
     */
    private boolean enabled = true;

    /**
     * 拦截处理模式：FAIL_FAST 快速抛出异常阻断，REPLACE 替换为安全提示文本
     */
    private GuardrailMode mode = GuardrailMode.FAIL_FAST;

    /**
     * 当模式为 REPLACE 或被拦截时返回的兜底友好提示文本
     */
    private String blockResponseText = "抱歉，您输入的内容或模型生成的内容包含不合规信息，已被安全护栏拦截。";

    /**
     * 最大提示词字符长度阈值（超过此长度判定为超限风险），默认 10000，设为 0 或负数表示不限
     */
    private int maxPromptLength = 10000;

    /**
     * 自定义敏感违禁词列表 (通过纯原生高效 Trie 树极速匹配，如涉黄涉暴、违禁关键字等)
     */
    private List<String> bannedKeywords = new ArrayList<>();

    /**
     * 自定义越狱/提示词注入正则表达式列表 (忽略大小写匹配)
     */
    private List<String> injectionPatterns = new ArrayList<>();

    /**
     * 免检白名单词列表 (命中白名单时豁免敏感词拦截)
     */
    private List<String> whitelist = new ArrayList<>();
}
