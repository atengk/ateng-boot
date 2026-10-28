package io.github.atengk.boot.realtime.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 单用户会话连接数超额驱逐策略枚举
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Getter
@RequiredArgsConstructor
public enum EvictionPolicy {

    /**
     * 先进先出：当连接数超过配额上限时，强制踢除最早建立的旧会话，接纳新连接
     */
    FIFO("fifo", "踢旧留新"),

    /**
     * 拒绝策略：当连接数达到上限时，直接拒绝建立新连接
     */
    REJECT("reject", "拒绝新连接");

    private final String code;
    private final String description;
}
