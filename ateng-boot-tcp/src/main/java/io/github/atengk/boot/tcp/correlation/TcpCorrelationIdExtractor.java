package io.github.atengk.boot.tcp.correlation;

/**
 * TCP 关联标识提取器函数式契约
 * <p>
 * 用于从入站通信报文或载荷中提取全双工 RPC 的消息关联流水号（requestId）。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
@FunctionalInterface
public interface TcpCorrelationIdExtractor {

    /**
     * 从消息实体中提取关联流水号
     *
     * @param message 报文或载荷对象（如 TcpPacket, TcpPayload, String, Map 等）
     * @return 关联流水号，若未能提取则返回 null
     */
    String extract(Object message);
}
