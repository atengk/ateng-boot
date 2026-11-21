package io.github.atengk.boot.sms.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.github.atengk.boot.core.enums.IBaseEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 短信单渠道配额重置周期枚举
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Getter
@AllArgsConstructor
public enum SmsQuotaResetCycle implements IBaseEnum<String> {

    /**
     * 按自然日自动清零
     */
    DAILY("DAILY", "按日清零"),

    /**
     * 总量包干（跨日累计，直至手动重置）
     */
    TOTAL("TOTAL", "总量包干");

    @JsonValue
    private final String code;

    private final String label;

    /**
     * 根据编码安全解析配额周期枚举
     *
     * @param code 编码或名称
     * @return 匹配的枚举，未匹配时返回 null
     */
    @JsonCreator
    public static SmsQuotaResetCycle fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(item -> item.code.equalsIgnoreCase(code.trim()) || item.name().equalsIgnoreCase(code.trim()))
                .findFirst()
                .orElse(null);
    }
}
