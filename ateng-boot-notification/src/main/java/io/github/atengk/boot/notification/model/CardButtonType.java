package io.github.atengk.boot.notification.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 交互卡片按钮展示样式类型枚举
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Getter
@AllArgsConstructor
public enum CardButtonType {

    /**
     * 主要操作按钮（高亮展示）
     */
    PRIMARY("primary"),

    /**
     * 次要操作按钮（默认外观）
     */
    DEFAULT("default"),

    /**
     * 警示/危险操作按钮（警示红展示）
     */
    DANGER("danger");

    private final String code;
}
