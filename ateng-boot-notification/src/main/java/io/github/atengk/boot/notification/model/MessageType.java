package io.github.atengk.boot.notification.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 协同通知消息载荷类型枚举
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Getter
@AllArgsConstructor
public enum MessageType {

    /**
     * 纯文本消息
     */
    TEXT("text", "纯文本消息"),

    /**
     * Markdown 富文本消息
     */
    MARKDOWN("markdown", "Markdown 富文本消息"),

    /**
     * 统一跨平台交互卡片消息
     */
    CARD("card", "统一交互卡片消息"),

    /**
     * 轻量图文链接消息
     */
    LINK("link", "图文链接消息"),

    /**
     * 图片消息
     */
    IMAGE("image", "图片消息"),

    /**
     * 原生特化 JSON 逃生舱消息
     */
    RAW("raw", "原生特化逃生舱消息");

    private final String code;
    private final String description;
}
