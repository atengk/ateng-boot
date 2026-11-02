package io.github.atengk.boot.tcp.enums;

/**
 * TCP 出站背压流控策略枚举
 * <p>
 * 定义当底层 Netty 网络通道出站缓冲区达到高水位（isWritable == false）时的应对行为。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-02
 */
public enum BackpressurePolicy {

    /**
     * 快速失败：当通道不可写时立即抛出 TcpBackpressureException，严防堆外内存无界堆积
     */
    FAIL_FAST,

    /**
     * 阻塞等待：在配置的超时窗口内阻塞等待通道恢复可写，超时后抛出 TcpBackpressureException
     */
    BLOCK_TIMEOUT,

    /**
     * 警告并发送：记录警告日志后继续强制写入 Netty 出站缓冲
     */
    WARN_AND_SEND
}
