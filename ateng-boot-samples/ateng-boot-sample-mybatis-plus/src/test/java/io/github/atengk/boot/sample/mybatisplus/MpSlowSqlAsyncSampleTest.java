package io.github.atengk.boot.sample.mybatisplus;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.mybatisplus.slowsql.SlowSqlEvent;
import io.github.atengk.boot.sample.mybatisplus.service.SlowSqlAlertScenarioService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 慢 SQL 异步告警派发端到端集成测试
 * 验证开启 {@code ateng.boot.mybatis-plus.slow-sql.async-enabled=true} 后依托 Core 受管线程池异步派发事件
 *
 * @author Ateng
 * @since 2026-11-13
 */
@SpringBootTest(properties = {
        "ateng.boot.mybatis-plus.slow-sql.enabled=true",
        "ateng.boot.mybatis-plus.slow-sql.threshold=0ms",
        "ateng.boot.mybatis-plus.slow-sql.async-enabled=true"
})
@DisplayName("切片3扩展：慢 SQL 异步派发端到端集成测试")
class MpSlowSqlAsyncSampleTest {

    @Autowired
    private SlowSqlAlertScenarioService slowSqlAlertScenarioService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        slowSqlAlertScenarioService.clearAlerts();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        jdbcTemplate.execute("DELETE FROM sys_user");
    }

    @Test
    @DisplayName("验证 slow-sql.async-enabled=true 时异步投递 SlowSqlEvent 至 SlowSqlAlertProvider 且完成密码脱敏")
    void shouldDispatchSlowSqlEventAsynchronouslyWithCoreExecutor() throws Exception {
        slowSqlAlertScenarioService.createUserWithEncryptedFields(
                "async_slow_user", 29, "async@example.com", "13800001111", 101L);
        slowSqlAlertScenarioService.clearAlerts();

        slowSqlAlertScenarioService.triggerSlowSqlWithPasswordParam("async_slow_user", "PlainPass@123");

        // 等待 Core 受管线程池异步回调完成（最迟 3 秒）
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        List<SlowSqlEvent> events = List.of();
        while (System.nanoTime() < deadline) {
            events = slowSqlAlertScenarioService.getCapturedAlerts();
            boolean matched = events.stream()
                    .anyMatch(e -> e.getStatementId() != null
                            && e.getStatementId().contains("selectByUsernameAndPassword"));
            if (matched) {
                break;
            }
            Thread.sleep(20L);
        }

        SlowSqlEvent targetEvent = events.stream()
                .filter(e -> e.getStatementId() != null
                        && e.getStatementId().contains("selectByUsernameAndPassword"))
                .findFirst()
                .orElse(null);

        assertThat(targetEvent).isNotNull();
        assertThat(targetEvent.getSql()).doesNotContain("PlainPass@123");
    }
}
