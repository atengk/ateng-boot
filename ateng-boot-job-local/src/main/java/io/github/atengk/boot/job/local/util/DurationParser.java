package io.github.atengk.boot.job.local.util;

import org.springframework.boot.convert.DurationStyle;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 时间间隔与超时格式解析工具
 * <p>
 * 支持标准 Spring Boot 风格的时间文本（如 500ms, 1s, 5m, 2h, 1d）以及纯数字毫秒值。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-25
 */
public final class DurationParser {

    private DurationParser() {
    }

    /**
     * 解析时间文本为 {@link Duration}
     *
     * @param text 时间文本（如 "10s", "1m", "500ms"）
     * @return 解析后的 Duration 对象，若输入为空或空白则返回 null
     * @throws IllegalArgumentException 若文本格式非法
     */
    public static Duration parse(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        return DurationStyle.detectAndParse(text.trim());
    }

    /**
     * 解析时间文本为 {@link Duration}，若为空则返回默认值
     *
     * @param text         时间文本
     * @param defaultValue 默认值
     * @return 解析后的 Duration 或默认值
     */
    public static Duration parse(String text, Duration defaultValue) {
        Duration duration = parse(text);
        return duration != null ? duration : defaultValue;
    }
}
