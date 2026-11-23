package io.github.atengk.boot.ai.sanitizer;

import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.core.util.ValidatorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 结构化输出防幻觉去噪纯函数工具类
 * 负责剥除大模型输出中携带的 Markdown 代码块语法糖（```json ... ```）、首尾不可见字符及对话噪音，
 * 并配合 Jackson 序列化与 JSR-303 规范进行强类型反序列化和数据防卫。
 *
 * @author Ateng
 * @since 2026-11-03
 */
public final class JsonPayloadSanitizer {

    private static final Logger log = LoggerFactory.getLogger(JsonPayloadSanitizer.class);

    /**
     * 匹配 Markdown 代码块的正则表达式（捕获语言标记与代码块内容）
     */
    private static final Pattern MARKDOWN_CODE_BLOCK_PATTERN = Pattern.compile(
            "```([a-zA-Z0-9_-]*)\\s*([\\s\\S]*?)\\s*```"
    );

    private JsonPayloadSanitizer() {
        // 工具类私有构造
    }

    /**
     * 判断文本中是否包含 Markdown JSON 代码块（```json ... ``` 或未标记语言但呈现纯 JSON 结构的 ``` ... ```）
     *
     * @param raw 原始文本
     * @return 是否包含 Markdown JSON 代码块
     */
    public static boolean hasMarkdownJsonBlock(String raw) {
        if (!StringUtils.hasText(raw) || !raw.contains("```")) {
            return false;
        }
        Matcher matcher = MARKDOWN_CODE_BLOCK_PATTERN.matcher(raw);
        while (matcher.find()) {
            String lang = matcher.group(1).trim().toLowerCase();
            String content = matcher.group(2).trim();
            if ("json".equals(lang) || (lang.isEmpty() && isJsonLikely(content))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 对大模型原始文本输出进行去噪清洗，提取纯净的 JSON 字符串
     *
     * @param raw 大模型原始输出字符串
     * @return 清洗去噪后的 JSON 字符串；若入参为空则返回空字符串
     */
    public static String sanitize(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }

        // 1. 基础预清洗：去除 UTF-8 BOM 和首尾空白
        String text = raw.replace("\uFEFF", "")
                .replace("\u200B", "")
                .replace("\u200C", "")
                .replace("\u200D", "")
                .trim();

        if (text.isEmpty()) {
            return "";
        }

        // 2. 尝试从 Markdown 代码块（```json ... ``` 或空语言 JSON 块）中提取内容
        Matcher matcher = MARKDOWN_CODE_BLOCK_PATTERN.matcher(text);
        while (matcher.find()) {
            String lang = matcher.group(1).trim().toLowerCase();
            String content = matcher.group(2).trim();
            if ("json".equals(lang) || (lang.isEmpty() && isJsonLikely(content))) {
                if (StringUtils.hasText(content)) {
                    return extractJsonBrackets(content);
                }
            }
        }

        // 3. 兜底处理：若无完整代码块，但在首尾包含非 JSON 对话噪点（如 "Here is the result: {...}"）
        return extractJsonBrackets(text);
    }

    /**
     * 快速启发式判断字符串是否可能为 JSON 对象或数组
     */
    private static boolean isJsonLikely(String content) {
        if (!StringUtils.hasText(content)) {
            return false;
        }
        String trimmed = content.trim();
        return (trimmed.startsWith("{") && trimmed.endsWith("}"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"));
    }

    /**
     * 从字符串中提取最外层的 JSON 对象（{ ... }）或 JSON 数组（[ ... ]）
     *
     * @param input 候选字符串
     * @return 提取并裁剪后的 JSON 字符串
     */
    private static String extractJsonBrackets(String input) {
        if (input == null) {
            return "";
        }
        String trimmed = input.trim();
        int firstObjBrace = trimmed.indexOf('{');
        int lastObjBrace = trimmed.lastIndexOf('}');
        int firstArrBracket = trimmed.indexOf('[');
        int lastArrBracket = trimmed.lastIndexOf(']');

        // 判断是最外层是对象还是数组
        boolean hasObject = firstObjBrace != -1 && lastObjBrace > firstObjBrace;
        boolean hasArray = firstArrBracket != -1 && lastArrBracket > firstArrBracket;

        if (hasObject && hasArray) {
            // 谁先出现就以谁为外围边界
            if (firstObjBrace < firstArrBracket) {
                return trimmed.substring(firstObjBrace, lastObjBrace + 1).trim();
            } else {
                return trimmed.substring(firstArrBracket, lastArrBracket + 1).trim();
            }
        } else if (hasObject) {
            return trimmed.substring(firstObjBrace, lastObjBrace + 1).trim();
        } else if (hasArray) {
            return trimmed.substring(firstArrBracket, lastArrBracket + 1).trim();
        }

        return trimmed;
    }

    /**
     * 清洗大模型输出并反序列化为目标强类型对象，随后强制执行 JSR-303 数据校验防卫
     *
     * @param raw         大模型原始输出字符串
     * @param targetClass 目标类型 Class
     * @param <T>         泛型类型
     * @param groups      校验分组
     * @return 校验通过的强类型对象实例
     * @throws AiException 若清洗后内容为空、JSON 反序列化失败或未通过 JSR-303 格式防卫
     */
    public static <T> T parseAndValidate(String raw, Class<T> targetClass, Class<?>... groups) {
        Objects.requireNonNull(targetClass, "目标反序列化类型 targetClass 不能为空");

        String sanitized = sanitize(raw);
        if (!StringUtils.hasText(sanitized)) {
            throw new AiException(AiResultCode.AI_RESPONSE_PARSE_ERROR,
                    "大模型返回内容为空或未包含合法的 JSON 结构，无法解析为类型 [" + targetClass.getSimpleName() + "]");
        }

        T result;
        try {
            result = JsonUtil.parseObject(sanitized, targetClass);
        } catch (Exception e) {
            throw new AiException(AiResultCode.AI_RESPONSE_PARSE_ERROR,
                    "大模型响应反序列化为 [" + targetClass.getSimpleName() + "] 失败: " + e.getMessage(), e);
        }

        if (result == null) {
            throw new AiException(AiResultCode.AI_RESPONSE_PARSE_ERROR,
                    "大模型响应反序列化为 [" + targetClass.getSimpleName() + "] 得到 null");
        }

        // JSR-303 防卫校验
        try {
            ValidatorUtil.validate(result, groups);
        } catch (BusinessException e) {
            throw new AiException(AiResultCode.AI_RESPONSE_PARSE_ERROR,
                    "大模型结构化响应未通过 JSR-303 校验: " + e.getMessage(), e);
        } catch (Throwable t) {
            // 若环境中未引入 Bean Validation 实现（如缺少 hibernate-validator），记录调试日志并平滑跳过校验
            if (t.getClass().getName().contains("NoProviderFoundException")) {
                log.debug("未探测到 Jakarta Bean Validation 校验器实现，跳过强类型输出的 JSR-303 校验");
            } else {
                throw t;
            }
        }

        return result;
    }
}
