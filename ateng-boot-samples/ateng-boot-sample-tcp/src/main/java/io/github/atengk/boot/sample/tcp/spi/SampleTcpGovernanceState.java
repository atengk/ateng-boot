package io.github.atengk.boot.sample.tcp.spi;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * TCP SPI 与网关治理运行态可观测状态存储器
 * 线程安全地聚合通道定制、出入站报文过滤、关联 ID 提取与函数式 SPI 处理器审计指标
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Component
public class SampleTcpGovernanceState {

    private static final int MAX_AUDIT_LOGS = 100;

    private final AtomicLong inboundPassedCount = new AtomicLong(0);
    private final AtomicLong inboundBlockedCount = new AtomicLong(0);
    private final AtomicLong outboundPassedCount = new AtomicLong(0);
    private final AtomicLong serverChannelsCustomized = new AtomicLong(0);
    private final AtomicLong clientChannelsCustomized = new AtomicLong(0);
    private final AtomicLong correlationExtractedCount = new AtomicLong(0);
    private final AtomicLong auditPingHandledCount = new AtomicLong(0);

    private final Set<String> customizedTargets = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedDeque<String> auditLogs = new ConcurrentLinkedDeque<>();
    private final ReentrantLock auditLogLock = new ReentrantLock();

    private volatile String lastAuditCorrelationId;
    private volatile String lastAuditPayloadSummary;
    private volatile long lastAuditTimestamp;

    /**
     * 记录入站报文放行
     */
    public void recordInboundPassed() {
        inboundPassedCount.incrementAndGet();
    }

    /**
     * 记录入站报文被过滤器拦截并附带审计日志
     *
     * @param reason 拦截原因摘要
     */
    public void recordInboundBlocked(String reason) {
        inboundBlockedCount.incrementAndGet();
        appendAuditLog("FILTER_BLOCKED: " + reason);
    }

    /**
     * 记录出站报文经过过滤器
     */
    public void recordOutboundPassed() {
        outboundPassedCount.incrementAndGet();
    }

    /**
     * 记录 Netty 通道初始化定制事件
     *
     * @param role 通道角色（SERVER / CLIENT）
     * @param name 实例名称
     */
    public void recordChannelCustomized(String role, String name) {
        String safeRole = role != null ? role : "UNKNOWN";
        String safeName = name != null ? name : "default";
        if ("SERVER".equalsIgnoreCase(safeRole)) {
            serverChannelsCustomized.incrementAndGet();
        } else {
            clientChannelsCustomized.incrementAndGet();
        }
        customizedTargets.add(safeRole + ":" + safeName);
    }

    /**
     * 记录自定义相关性提取器命中次数
     */
    public void recordCorrelationExtracted() {
        correlationExtractedCount.incrementAndGet();
    }

    /**
     * 记录函数式 SPI 处理器审计事件
     *
     * @param correlationId 请求关联流水号
     * @param summary       载荷摘要
     */
    public void recordAuditPing(String correlationId, String summary) {
        auditPingHandledCount.incrementAndGet();
        this.lastAuditCorrelationId = correlationId;
        this.lastAuditPayloadSummary = summary;
        this.lastAuditTimestamp = System.currentTimeMillis();
        appendAuditLog("AUDIT_PING[corr=" + correlationId + "]: " + summary);
    }

    private void appendAuditLog(String entry) {
        auditLogLock.lock();
        try {
            auditLogs.addFirst(entry);
            while (auditLogs.size() > MAX_AUDIT_LOGS) {
                auditLogs.pollLast();
            }
        } finally {
            auditLogLock.unlock();
        }
    }

    /**
     * 清空运行态审计记录与计数器（多用于测试隔离重置）
     */
    public void reset() {
        inboundPassedCount.set(0);
        inboundBlockedCount.set(0);
        outboundPassedCount.set(0);
        correlationExtractedCount.set(0);
        auditPingHandledCount.set(0);
        auditLogLock.lock();
        try {
            auditLogs.clear();
        } finally {
            auditLogLock.unlock();
        }
        lastAuditCorrelationId = null;
        lastAuditPayloadSummary = null;
        lastAuditTimestamp = 0L;
    }

    public long getInboundPassedCount() {
        return inboundPassedCount.get();
    }

    public long getInboundBlockedCount() {
        return inboundBlockedCount.get();
    }

    public long getOutboundPassedCount() {
        return outboundPassedCount.get();
    }

    public long getServerChannelsCustomized() {
        return serverChannelsCustomized.get();
    }

    public long getClientChannelsCustomized() {
        return clientChannelsCustomized.get();
    }

    public Set<String> getCustomizedTargets() {
        return Collections.unmodifiableSet(customizedTargets);
    }

    public long getCorrelationExtractedCount() {
        return correlationExtractedCount.get();
    }

    public long getAuditPingHandledCount() {
        return auditPingHandledCount.get();
    }

    public String getLastAuditCorrelationId() {
        return lastAuditCorrelationId;
    }

    public String getLastAuditPayloadSummary() {
        return lastAuditPayloadSummary;
    }

    public long getLastAuditTimestamp() {
        return lastAuditTimestamp;
    }

    public List<String> getAuditLogs() {
        return new ArrayList<>(auditLogs);
    }
}
