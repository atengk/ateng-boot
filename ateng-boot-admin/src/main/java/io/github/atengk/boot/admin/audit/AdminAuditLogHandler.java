package io.github.atengk.boot.admin.audit;

/**
 * 控制台操作审计处理器扩展点 SPI（零 DB 侵入）
 *
 * @author Ateng
 * @since 2026-11-21
 */
@FunctionalInterface
public interface AdminAuditLogHandler {

    /**
     * 处理一条已完成脱敏的结构化审计记录
     *
     * @param record 不可变审计记录
     */
    void handle(AdminAuditRecord record);
}
