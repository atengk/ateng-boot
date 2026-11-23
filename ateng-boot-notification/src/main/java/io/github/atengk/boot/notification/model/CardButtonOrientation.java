package io.github.atengk.boot.notification.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 交互卡片按钮排版方向枚举
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Getter
@AllArgsConstructor
public enum CardButtonOrientation {

    /**
     * 竖直排列（钉钉 ActionCard 对应 btnOrientation="0"）
     */
    VERTICAL("0", "竖向排列"),

    /**
     * 横向排列（钉钉 ActionCard 对应 btnOrientation="1"）
     */
    HORIZONTAL("1", "横向排列");

    private final String code;
    private final String description;
}
