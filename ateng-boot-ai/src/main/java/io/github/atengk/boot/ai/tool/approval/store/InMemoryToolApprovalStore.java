package io.github.atengk.boot.ai.tool.approval.store;

import io.github.atengk.boot.ai.tool.approval.ToolApprovalStatus;
import io.github.atengk.boot.ai.tool.approval.ToolApprovalTicket;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单机并发内存高危工具审批工单存储实现类
 * <p>
 * 采用并发安全容器暂存工单，基于访问期惰性探测与主动清理消除超时内存膨胀，
 * 适用于单机开发联调与 100% 离线自洽测试沙箱。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-06
 */
public class InMemoryToolApprovalStore implements ToolApprovalStore {

    private final Map<String, ToolApprovalTicket> tickets = new ConcurrentHashMap<>();
    private final Map<String, Instant> expireAtMap = new ConcurrentHashMap<>();

    @Override
    public void save(ToolApprovalTicket ticket, Duration ttl) {
        Objects.requireNonNull(ticket, "ticket must not be null");
        if (!StringUtils.hasText(ticket.getTicketId())) {
            throw new IllegalArgumentException("ticketId must not be empty");
        }
        Duration effectiveTtl = (ttl != null && !ttl.isNegative() && !ttl.isZero()) ? ttl : Duration.ofMinutes(30);
        tickets.put(ticket.getTicketId(), ticket);
        expireAtMap.put(ticket.getTicketId(), Instant.now().plus(effectiveTtl));
    }

    @Override
    public Optional<ToolApprovalTicket> findById(String ticketId) {
        if (!StringUtils.hasText(ticketId)) {
            return Optional.empty();
        }
        cleanIfExpired(ticketId);
        return Optional.ofNullable(tickets.get(ticketId));
    }

    @Override
    public boolean updateStatus(String ticketId,
                                ToolApprovalStatus expectedStatus,
                                ToolApprovalStatus newStatus,
                                String comment,
                                String executionResultJson,
                                String errorMessage) {
        if (!StringUtils.hasText(ticketId)) {
            return false;
        }
        cleanIfExpired(ticketId);

        final boolean[] updated = {false};
        tickets.computeIfPresent(ticketId, (id, ticket) -> {
            if (ticket.getStatus() == expectedStatus) {
                ticket.setStatus(newStatus);
                ticket.setReviewComment(comment);
                ticket.setReviewTime(LocalDateTime.now());
                ticket.setExecutionResultJson(executionResultJson);
                ticket.setErrorMessage(errorMessage);
                updated[0] = true;
            }
            return ticket;
        });
        return updated[0];
    }

    @Override
    public boolean delete(String ticketId) {
        if (!StringUtils.hasText(ticketId)) {
            return false;
        }
        expireAtMap.remove(ticketId);
        return tickets.remove(ticketId) != null;
    }

    @Override
    public List<ToolApprovalTicket> listPending() {
        cleanAllExpired();
        List<ToolApprovalTicket> result = new ArrayList<>();
        for (ToolApprovalTicket ticket : tickets.values()) {
            if (ticket.getStatus() == ToolApprovalStatus.PENDING) {
                result.add(ticket);
            }
        }
        return Collections.unmodifiableList(result);
    }

    private void cleanIfExpired(String ticketId) {
        Instant expireAt = expireAtMap.get(ticketId);
        if (expireAt != null && Instant.now().isAfter(expireAt)) {
            ToolApprovalTicket ticket = tickets.get(ticketId);
            if (ticket != null && ticket.getStatus() == ToolApprovalStatus.PENDING) {
                ticket.setStatus(ToolApprovalStatus.EXPIRED);
            }
            expireAtMap.remove(ticketId);
        }
    }

    private void cleanAllExpired() {
        Instant now = Instant.now();
        for (Map.Entry<String, Instant> entry : expireAtMap.entrySet()) {
            if (now.isAfter(entry.getValue())) {
                String ticketId = entry.getKey();
                ToolApprovalTicket ticket = tickets.get(ticketId);
                if (ticket != null && ticket.getStatus() == ToolApprovalStatus.PENDING) {
                    ticket.setStatus(ToolApprovalStatus.EXPIRED);
                }
                expireAtMap.remove(ticketId);
            }
        }
    }

    public void clear() {
        tickets.clear();
        expireAtMap.clear();
    }
}
