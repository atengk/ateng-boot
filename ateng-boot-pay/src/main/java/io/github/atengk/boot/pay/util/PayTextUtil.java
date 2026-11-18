package io.github.atengk.boot.pay.util;

import io.github.atengk.boot.core.util.StringUtil;

/**
 * 支付模块内部字符串与文本安全处理辅助工具
 *
 * @author Ateng
 * @since 2026-11-18
 */
public final class PayTextUtil {

    private PayTextUtil() {
    }

    /**
     * 判断字符串是否包含非空白有效文本
     *
     * @param value 待检查文本
     * @return true 若非空且包含至少一个非空白字符
     */
    public static boolean hasText(String value) {
        return StringUtil.hasText(value);
    }

    /**
     * 顺序返回首个非空白候选文本并自动去除首尾空白
     *
     * @param candidates 候选文本列表
     * @return 首个非空白且 trim 后的文本，若全部为空白则返回 {@code null}
     */
    public static String firstNonBlankText(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            if (hasText(candidate)) {
                return candidate.trim();
            }
        }
        return null;
    }
}
