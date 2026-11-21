package io.github.atengk.boot.admin.audit;

import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 控制台操作审计发布与回调协调服务
 * <p>
 * 负责在审计开启时构建脱敏 {@link AdminAuditRecord}，广播 {@link AdminAuditEvent} 领域事件，
 * 并隔离调度容器中注册的全部 {@link AdminAuditLogHandler} SPI 实例，确保下游审计异常不阻断主请求链路。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Slf4j
public class AdminAuditService {

    private final AtengAdminServerProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final List<AdminAuditLogHandler> handlers;
    private final Clock clock;

    public AdminAuditService(AtengAdminServerProperties properties,
                             ApplicationEventPublisher eventPublisher,
                             List<AdminAuditLogHandler> handlers) {
        this(properties, eventPublisher, handlers, Clock.systemUTC());
    }

    public AdminAuditService(AtengAdminServerProperties properties,
                             ApplicationEventPublisher eventPublisher,
                             List<AdminAuditLogHandler> handlers,
                             Clock clock) {
        this.properties = properties;
        this.eventPublisher = eventPublisher;
        this.handlers = handlers != null ? List.copyOf(handlers) : Collections.emptyList();
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 记录一条控制台审计轨迹并触发事件广播与处理器链
     *
     * @param action   审计动作类型
     * @param actor    操作主体标识
     * @param sourceIp 客户端来源 IP
     * @param target   操作目标标识
     * @param method   HTTP 方法或操作方式
     * @param path     请求路径
     * @param outcome  操作结果
     * @param detail   补充细节
     */
    public void record(AdminAuditAction action,
                       String actor,
                       String sourceIp,
                       String target,
                       String method,
                       String path,
                       String outcome,
                       String detail) {
        if (action == null || !isAuditEnabled()) {
            return;
        }
        AdminAuditRecord auditRecord = AdminAuditRecord.of(
                action, actor, sourceIp, target, method, path, outcome, detail, clock.instant()
        );
        if (eventPublisher != null) {
            try {
                eventPublisher.publishEvent(new AdminAuditEvent(this, auditRecord));
            } catch (Exception ex) {
                log.warn("[Admin Audit] 发布审计领域事件异常已隔离: action=[{}], err=[{}]",
                        action.getCode(), ex.getMessage());
            }
        }
        for (AdminAuditLogHandler handler : handlers) {
            if (handler == null) {
                continue;
            }
            try {
                handler.handle(auditRecord);
            } catch (Exception ex) {
                log.warn("[Admin Audit] 执行审计处理器异常已隔离: handler=[{}], action=[{}], err=[{}]",
                        handler.getClass().getSimpleName(), action.getCode(), ex.getMessage());
            }
        }
    }

    private boolean isAuditEnabled() {
        return properties == null
                || properties.getAudit() == null
                || properties.getAudit().isEnabled();
    }

    /**
     * 空安全静态委托：当审计服务实例存在时记录结构化审计轨迹
     *
     * @param service  审计协调服务（可为 null）
     * @param action   审计动作类型
     * @param actor    操作主体标识
     * @param sourceIp 客户端来源 IP
     * @param target   操作目标标识
     * @param method   HTTP 方法或操作方式
     * @param path     请求路径
     * @param outcome  操作结果
     * @param detail   补充细节
     */
    public static void recordIfPresent(AdminAuditService service,
                                       AdminAuditAction action,
                                       String actor,
                                       String sourceIp,
                                       String target,
                                       String method,
                                       String path,
                                       String outcome,
                                       String detail) {
        if (service != null) {
            service.record(action, actor, sourceIp, target, method, path, outcome, detail);
        }
    }
}
