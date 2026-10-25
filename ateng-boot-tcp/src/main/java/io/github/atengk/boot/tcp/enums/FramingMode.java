package io.github.atengk.boot.tcp.enums;

/**
 * TCP 数据帧切片模式枚举
 *
 * @author Ateng
 * @since 2026-10-26
 */
public enum FramingMode {

    /**
     * 不进行自动切片（透明透传原始字节流）
     */
    NONE,

    /**
     * 分隔符模式（按特定字符或字节分隔符切片，如换行符或特定结束符）
     */
    DELIMITER,

    /**
     * 固定长度模式（按指定固定字节长度切片）
     */
    FIXED_LENGTH,

    /**
     * 长度字段偏移模式（基于报文头特定偏移量的长度字段动态计算并切片）
     */
    LENGTH_FIELD
}
