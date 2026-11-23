package io.github.atengk.boot.admin.audit;

import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 控制台操作审计（有界环形仓库、领域事件发布、SPI 隔离与敏感字段脱敏）单元测试
 *
 * @author Ateng
 * @since 2026-11-21
 */
@DisplayName("AdminAudit 控制台结构化操作审计单元测试")
class AdminAuditLogTest {

    @Test
    @DisplayName("有界环形审计仓库超出 maxRecords 上限时按 FIFO 自动淘汰最旧记录，且支持按 action/actor 过滤查询")
    void shouldEvictOldestRecordsFifoAndFilterCorrectly() {
        AdminAuditLogRepository repository = new AdminAuditLogRepository(3);
        Clock clock = Clock.fixed(Instant.parse("2026-11-21T03:10:00Z"), ZoneOffset.UTC);

        for (int i = 1; i <= 5; i++) {
            AdminAuditAction action = (i % 2 == 0) ? AdminAuditAction.ENDPOINT_BLOCKED : AdminAuditAction.LOGIN_SUCCESS;
            String actor = (i <= 3) ? "alice" : "bob";
            repository.record(AdminAuditRecord.of(
                    action,
                    actor,
                    "10.1.0." + i,
                    "order-service",
                    "POST",
                    "/admin/instances/1/actuator/loggers",
                    "ALLOW",
                    "event-" + i,
                    clock.instant()
            ));
        }

        assertThat(repository.size()).isEqualTo(3);
        // 最新倒序：event-5 (bob), event-4 (bob), event-3 (alice)
        List<AdminAuditRecord> all = repository.query(null, null, 10);
        assertThat(all).extracting(AdminAuditRecord::getDetail)
                .containsExactly("event-5", "event-4", "event-3");

        List<AdminAuditRecord> bobBlocked = repository.query(AdminAuditAction.ENDPOINT_BLOCKED, "bob", 10);
        assertThat(bobBlocked).hasSize(1);
        assertThat(bobBlocked.get(0).getDetail()).isEqualTo("event-4");
    }

    @Test
    @DisplayName("审计记录自动脱敏敏感凭据，AdminAuditService 发布领域事件并隔离异常 SPI")
    void shouldSanitizeSecretsAndIsolateBrokenHandler() {
        AtengAdminServerProperties properties = new AtengAdminServerProperties();
        properties.getAudit().setEnabled(true);
        properties.getAudit().setMaxRecords(50);

        AdminAuditLogRepository repository = new AdminAuditLogRepository(properties.getAudit().getMaxRecords());
        DefaultAdminAuditLogHandler defaultHandler = new DefaultAdminAuditLogHandler(repository);
        AdminAuditLogHandler brokenHandler = record -> {
            throw new IllegalStateException("downstream audit sink down");
        };

        List<AdminAuditEvent> publishedEvents = new ArrayList<>();
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof AdminAuditEvent auditEvent) {
                publishedEvents.add(auditEvent);
            }
        };

        Clock fixedClock = Clock.fixed(Instant.parse("2026-11-21T03:15:00Z"), ZoneOffset.UTC);
        AdminAuditService auditService = new AdminAuditService(
                properties,
                publisher,
                List.of(brokenHandler, defaultHandler),
                fixedClock
        );

        auditService.record(
                AdminAuditAction.LOGIN_FAILURE,
                "sre-admin",
                "203.0.113.5",
                "console",
                "POST",
                "/admin/login?password=SuperSecret123&token=Tok999",
                "DENIED",
                "password=SuperSecret123 X-Admin-Client-Secret=TOP_SECRET_888"
        );

        assertThat(publishedEvents).hasSize(1);
        AdminAuditRecord saved = publishedEvents.get(0).getRecord();
        assertThat(saved.getAction()).isEqualTo(AdminAuditAction.LOGIN_FAILURE);
        assertThat(saved.getActor()).isEqualTo("sre-admin");
        assertThat(saved.getMaskedActor()).isEqualTo("sr******n");
        assertThat(saved.getPath()).doesNotContain("SuperSecret123").doesNotContain("Tok999");
        assertThat(saved.getDetail()).doesNotContain("SuperSecret123").doesNotContain("TOP_SECRET_888");
        assertThat(repository.size()).isEqualTo(1);
    }
}
