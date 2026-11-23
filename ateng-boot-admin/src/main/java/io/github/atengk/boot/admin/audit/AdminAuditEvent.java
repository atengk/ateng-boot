package io.github.atengk.boot.admin.audit;

import org.springframework.context.ApplicationEvent;

import java.util.Objects;

/**
 * Spring Boot Admin 控制台操作审计领域事件
 * <p>
 * 业务模块可通过 {@code @EventListener(AdminAuditEvent.class)} 订阅并外发至 SIEM、数据库或独立日志平台。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-21
 */
public class AdminAuditEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    private final AdminAuditRecord record;

    public AdminAuditEvent(Object source, AdminAuditRecord record) {
        super(source);
        this.record = Objects.requireNonNull(record, "record must not be null");
    }

    /**
     * 获取不可变审计记录载荷
     *
     * @return 审计记录
     */
    public AdminAuditRecord getRecord() {
        return this.record;
    }
}
