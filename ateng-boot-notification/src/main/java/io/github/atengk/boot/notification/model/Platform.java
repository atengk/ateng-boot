package io.github.atengk.boot.notification.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 协同办公机器人平台类型枚举
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Getter
@AllArgsConstructor
public enum Platform {

    /**
     * 钉钉群机器人
     */
    DINGTALK("dingtalk", "钉钉群机器人"),

    /**
     * 企业微信群机器人
     */
    WECOM("wecom", "企业微信群机器人"),

    /**
     * 飞书群机器人
     */
    FEISHU("feishu", "飞书群机器人"),

    /**
     * 通用 JSON Webhook 机器人
     */
    GENERIC("generic", "通用 Webhook 机器人");

    private final String code;
    private final String description;

    /**
     * 根据编码获取平台枚举（忽略大小写）
     *
     * @param code 平台编码或名称
     * @return 匹配的平台枚举，若无匹配则返回 null
     */
    public static Platform fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(p -> p.code.equalsIgnoreCase(code) || p.name().equalsIgnoreCase(code))
                .findFirst()
                .orElse(null);
    }
}
