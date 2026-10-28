package io.github.atengk.boot.notification.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 交互卡片顶栏主题色枚举
 * 提供面向飞书模板色、钉钉色标与企业微信高亮色的自适应对齐映射
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Getter
@AllArgsConstructor
public enum CardColor {

    /**
     * 蓝色（默认信息色）
     */
    BLUE("blue", "#1890FF", "info"),

    /**
     * 绿色（成功色）
     */
    GREEN("green", "#52C41A", "info"),

    /**
     * 红色（错误/严重故障色）
     */
    RED("red", "#FF4D4F", "warning"),

    /**
     * 橙色（告警/注意色）
     */
    ORANGE("orange", "#FA8C16", "warning"),

    /**
     * 灰色（次要/普通色）
     */
    GREY("grey", "#8C8C8C", "comment"),

    /**
     * 紫色
     */
    PURPLE("purple", "#722ED1", "info"),

    /**
     * 胭脂红
     */
    CARMINE("carmine", "#EB2F96", "warning"),

    /**
     * 青绿色
     */
    TURQUOISE("turquoise", "#13C2C2", "info"),

    /**
     * 黄色
     */
    YELLOW("yellow", "#FADB14", "warning"),

    /**
     * 沃森蓝（深蓝）
     */
    WATSON("watson", "#2F54EB", "info");

    /**
     * 飞书交互卡片 Header Template 官方颜色标识符
     */
    private final String feishuTemplate;

    /**
     * 钉钉十六进制颜色值
     */
    private final String dingTalkHex;

    /**
     * 企业微信高亮配色关键字（info / comment / warning）
     */
    private final String weComColor;
}
