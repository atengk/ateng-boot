package io.github.atengk.boot.tcp.properties;

import lombok.Getter;
import lombok.Setter;

/**
 * TCP 基于长度字段切片参数配置
 *
 * @author Ateng
 * @since 2026-10-26
 */
@Getter
@Setter
public class TcpLengthFieldProperties {

    /**
     * 长度字段在报文中的起始偏移量（字节，默认 0）
     */
    private int offset = 0;

    /**
     * 长度字段所占用的字节数（仅支持 1, 2, 3, 4, 8，默认 4）
     */
    private int length = 4;

    /**
     * 长度补偿修正值（报文总长度与长度字段数值的偏差补偿，默认 0）
     */
    private int adjustment = 0;

    /**
     * 传递给后续业务处理器时需剥离的初始字节数（默认 4，即跳过长度字段本身）
     */
    private int initialBytesToStrip = 4;
}
