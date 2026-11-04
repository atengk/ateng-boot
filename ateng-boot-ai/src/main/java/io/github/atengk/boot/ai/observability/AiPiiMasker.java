package io.github.atengk.boot.ai.observability;

import io.github.atengk.boot.core.util.DesensitizeUtil;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 提示词与响应文本 PII 敏感信息脱敏器
 * 联动 Core 底座 DesensitizeUtil 针对出入参文本中出现的 API Key、手机号、身份证、银行卡等实施正则动态掩码
 *
 * @author Ateng
 * @since 2026-11-04
 */
public final class AiPiiMasker {

    private static final Pattern PHONE_PATTERN = Pattern.compile("(?<!\\d)(1[3-9]\\d{9})(?!\\d)");
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("(?<!\\d)(\\d{6}(?:18|19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx])(?!\\d)");
    private static final Pattern BANK_CARD_PATTERN = Pattern.compile("(?<!\\d)([1-9]\\d{15,18})(?!\\d)");
    private static final Pattern API_KEY_PATTERN = Pattern.compile("(?i)(sk-[a-zA-Z0-9_\\-]{10,})");

    private AiPiiMasker() {
    }

    /**
     * 对文本中的 API Key 及 PII 敏感信息进行脱敏处理
     *
     * @param text 原始输入或回答文本
     * @return 脱敏后的纯净文本
     */
    public static String mask(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        String result = text;
        // 1. 优先脱敏 API Key (如 sk-proj-...)
        result = maskPattern(result, API_KEY_PATTERN, DesensitizeUtil::secretKey);
        // 2. 脱敏手机号 (11位大陆号码)
        result = maskPattern(result, PHONE_PATTERN, DesensitizeUtil::mobilePhone);
        // 3. 脱敏身份证号 (18位大陆居民身份证)
        result = maskPattern(result, ID_CARD_PATTERN, DesensitizeUtil::idCard);
        // 4. 脱敏银行卡号 (16-19位卡号)
        result = maskPattern(result, BANK_CARD_PATTERN, DesensitizeUtil::bankCard);

        return result;
    }

    /**
     * 针对指定正则模式执行替换匹配与函数式脱敏
     *
     * @param input   输入文本
     * @param pattern 正则模式
     * @param masker  脱敏转换器
     * @return 替换后的文本
     */
    private static String maskPattern(String input, Pattern pattern, Function<String, String> masker) {
        Matcher matcher = pattern.matcher(input);
        if (!matcher.find()) {
            return input;
        }
        StringBuilder sb = new StringBuilder();
        do {
            String match = matcher.group(1);
            String replacement = masker.apply(match);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement != null ? replacement : match));
        } while (matcher.find());
        matcher.appendTail(sb);
        return sb.toString();
    }
}
