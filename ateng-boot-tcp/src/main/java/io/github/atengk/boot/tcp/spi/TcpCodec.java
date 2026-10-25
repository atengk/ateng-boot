package io.github.atengk.boot.tcp.spi;

import io.github.atengk.boot.tcp.model.TcpPayload;

/**
 * TCP 业务协议双向编解码器 SPI 契约
 *
 * @param <T> 业务强类型协议对象
 * @author Ateng
 * @since 2026-10-26
 */
public interface TcpCodec<T> {

    /**
     * 将业务对象编码为安全载荷实体
     *
     * @param message 业务对象
     * @return 载荷实体
     */
    TcpPayload encode(T message);

    /**
     * 将载荷实体解码为业务强类型对象
     *
     * @param payload 载荷实体
     * @return 业务对象
     */
    T decode(TcpPayload payload);
}
