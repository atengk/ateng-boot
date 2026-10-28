package io.github.atengk.boot.tcp.spi;

import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.session.TcpSession;

/**
 * TCP 消息处理器 SPI 契约
 * <p>
 * 面向复杂工业协议解析或直接函数式绑定的消息消费扩展点。
 * </p>
 *
 * @param <T> 业务载荷强类型
 * @author Ateng
 * @since 2026-10-26
 */
public interface TcpMessageHandler<T> {

    /**
     * 绑定的命令字
     *
     * @return 路由命令字
     */
    String getCommand();

    /**
     * 执行业务处理逻辑
     *
     * @param session 当前客户端会话
     * @param payload 反序列化后的载荷对象
     * @param packet  完整 TCP 报文包
     */
    void handle(TcpSession session, T payload, TcpPacket packet);
}
