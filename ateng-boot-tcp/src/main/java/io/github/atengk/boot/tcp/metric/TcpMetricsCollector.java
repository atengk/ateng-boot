package io.github.atengk.boot.tcp.metric;

import io.github.atengk.boot.tcp.session.TcpSessionManager;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TCP 网络黄金度量指标收集器
 * <p>
 * 依托 Micrometer 统一注册活跃长连接数、收发报文数、吞吐流量与 RPC 往返耗时指标。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
public class TcpMetricsCollector {

    public static final String METRIC_CONNECTIONS_ACTIVE = "ateng.tcp.connections.active";
    public static final String METRIC_CONNECTIONS_TOTAL = "ateng.tcp.connections.total";
    public static final String METRIC_MESSAGES_RECEIVED = "ateng.tcp.messages.received";
    public static final String METRIC_MESSAGES_SENT = "ateng.tcp.messages.sent";
    public static final String METRIC_BYTES_RECEIVED = "ateng.tcp.bytes.received";
    public static final String METRIC_BYTES_SENT = "ateng.tcp.bytes.sent";
    public static final String METRIC_REQUEST_DURATION = "ateng.tcp.request.duration";

    private final Counter connectionsTotal;
    private final Counter messagesReceived;
    private final Counter messagesSent;
    private final Counter bytesReceived;
    private final Counter bytesSent;
    private final Timer requestDuration;

    private final AtomicLong connectionsTotalCount = new AtomicLong(0);
    private final AtomicLong messagesReceivedCount = new AtomicLong(0);
    private final AtomicLong messagesSentCount = new AtomicLong(0);
    private final AtomicLong bytesReceivedCount = new AtomicLong(0);
    private final AtomicLong bytesSentCount = new AtomicLong(0);

    public TcpMetricsCollector() {
        this(null, null);
    }

    public TcpMetricsCollector(MeterRegistry meterRegistry) {
        this(meterRegistry, null);
    }

    public TcpMetricsCollector(MeterRegistry meterRegistry, TcpSessionManager sessionManager) {
        if (meterRegistry != null) {
            Gauge.builder(METRIC_CONNECTIONS_ACTIVE, sessionManager, mgr -> mgr != null ? mgr.getActiveCount() : 0)
                    .description("当前活跃 TCP 长连接总数")
                    .register(meterRegistry);

            this.connectionsTotal = Counter.builder(METRIC_CONNECTIONS_TOTAL)
                    .description("累计建立的 TCP 连接总数")
                    .register(meterRegistry);

            this.messagesReceived = Counter.builder(METRIC_MESSAGES_RECEIVED)
                    .description("累计接收到的 TCP 报文总数")
                    .register(meterRegistry);

            this.messagesSent = Counter.builder(METRIC_MESSAGES_SENT)
                    .description("累计发送的 TCP 报文总数")
                    .register(meterRegistry);

            this.bytesReceived = Counter.builder(METRIC_BYTES_RECEIVED)
                    .baseUnit("bytes")
                    .description("累计接收的网络字节总数")
                    .register(meterRegistry);

            this.bytesSent = Counter.builder(METRIC_BYTES_SENT)
                    .baseUnit("bytes")
                    .description("累计发送的网络字节总数")
                    .register(meterRegistry);

            this.requestDuration = Timer.builder(METRIC_REQUEST_DURATION)
                    .description("全双工 TCP RPC 请求-响应耗时")
                    .register(meterRegistry);
        } else {
            this.connectionsTotal = null;
            this.messagesReceived = null;
            this.messagesSent = null;
            this.bytesReceived = null;
            this.bytesSent = null;
            this.requestDuration = null;
        }
    }

    public void recordConnection() {
        connectionsTotalCount.incrementAndGet();
        if (connectionsTotal != null) {
            connectionsTotal.increment();
        }
    }

    public void recordMessageReceived(long bytes) {
        messagesReceivedCount.incrementAndGet();
        bytesReceivedCount.addAndGet(Math.max(0, bytes));
        if (messagesReceived != null) {
            messagesReceived.increment();
        }
        if (bytesReceived != null && bytes > 0) {
            bytesReceived.increment(bytes);
        }
    }

    public void recordMessageSent(long bytes) {
        messagesSentCount.incrementAndGet();
        bytesSentCount.addAndGet(Math.max(0, bytes));
        if (messagesSent != null) {
            messagesSent.increment();
        }
        if (bytesSent != null && bytes > 0) {
            bytesSent.increment(bytes);
        }
    }

    public void recordRequestDuration(Duration duration) {
        if (requestDuration != null && duration != null && !duration.isNegative()) {
            requestDuration.record(duration);
        }
    }

    public long getConnectionsTotal() {
        return connectionsTotalCount.get();
    }

    public long getMessagesReceived() {
        return messagesReceivedCount.get();
    }

    public long getMessagesSent() {
        return messagesSentCount.get();
    }

    public long getBytesReceived() {
        return bytesReceivedCount.get();
    }

    public long getBytesSent() {
        return bytesSentCount.get();
    }
}
