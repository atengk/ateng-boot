package io.github.atengk.boot.admin.audit;

import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

/**
 * 默认控制台操作审计处理器：输出结构化 SLF4J 审计日志并写入有界内存环形仓库
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Slf4j
public class DefaultAdminAuditLogHandler implements AdminAuditLogHandler {

    private final AdminAuditLogRepository repository;

    public DefaultAdminAuditLogHandler(AdminAuditLogRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    @Override
    public void handle(AdminAuditRecord record) {
        if (record == null) {
            return;
        }
        repository.record(record);
        log.info("[Admin Audit] id=[{}], action=[{}], actor=[{}], ip=[{}], method=[{}], path=[{}], target=[{}], outcome=[{}], detail=[{}]",
                record.getId(),
                record.getAction().getCode(),
                record.getMaskedActor(),
                record.getSourceIp(),
                record.getMethod(),
                record.getPath(),
                record.getTarget(),
                record.getOutcome(),
                record.getDetail());
    }
}
