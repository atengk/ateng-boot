package io.github.atengk.boot.sample.mybatisplus;

import io.github.atengk.boot.mybatisplus.slowsql.SlowSqlEvent;
import io.github.atengk.boot.sample.mybatisplus.model.UserDynamicVO;
import io.github.atengk.boot.sample.mybatisplus.service.SlowSqlAlertScenarioService;
import io.github.atengk.boot.sample.mybatisplus.slowsql.SampleSlowSqlAlertProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 慢 SQL 性能拦截、敏感脱敏与告警端到端集成测试
 * 验证：
 * 1. 执行耗时 SQL 超过阈值时触发 SlowSqlInterceptor 并由 SampleSlowSqlAlertProvider 捕获告警
 * 2. 格式化可执行 SQL 美化缩进与代码调用栈行号精准定位
 * 3. @FieldEncrypt 敏感字段与密码凭据在慢 SQL 告警参数列表中安全脱敏掩码为 ******
 * 4. 防御性空安全：无匹配记录保底返回且不影响告警捕获（出参统一为强类型 VO，零 Entity 泄露）
 *
 * @author Ateng
 * @since 2026-10-28
 */
@SpringBootTest(properties = {
        "ateng.boot.mybatis-plus.slow-sql.enabled=true",
        "ateng.boot.mybatis-plus.slow-sql.threshold=0ms",
        "ateng.boot.mybatis-plus.slow-sql.format-sql=true",
        "ateng.boot.mybatis-plus.slow-sql.mask-sensitive=true"
})
@DisplayName("慢 SQL 性能拦截与脱敏告警端到端测试")
class SlowSqlAlertScenarioTest {

    @Autowired
    private SlowSqlAlertScenarioService scenarioService;

    @Autowired
    private SampleSlowSqlAlertProvider alertProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        alertProvider.clear();
        jdbcTemplate.update("DELETE FROM sys_user WHERE name LIKE 'SlowSql%'");
    }

    @AfterEach
    void tearDown() {
        alertProvider.clear();
        jdbcTemplate.update("DELETE FROM sys_user WHERE name LIKE 'SlowSql%'");
    }

    @Test
    @DisplayName("执行查询超过阈值时触发 SlowSqlInterceptor 并由 SampleSlowSqlAlertProvider 精准捕获")
    void shouldCaptureSlowSqlAlertWhenQueryExceedsThreshold() {
        UserDynamicVO user = scenarioService.getUserById(1L);

        List<SlowSqlEvent> alerts = alertProvider.getEvents();
        assertThat(alerts).isNotEmpty();

        SlowSqlEvent event = alerts.get(alerts.size() - 1);
        assertThat(event.getStatementId()).contains("UserMapper.selectById");
        assertThat(event.getSql()).contains("SELECT");
        assertThat(event.getExecutionTime()).isNotNull();
        assertThat(event.getThreshold()).isEqualTo(Duration.ZERO);
        assertThat(event.getTriggerTime()).isNotNull();
        assertThat(event.getThreadName()).isEqualTo(Thread.currentThread().getName());
    }

    @Test
    @DisplayName("慢 SQL 告警包含格式化 SQL 缩进文本与精准代码调用栈位置")
    void shouldFormatSqlAndLocateCallStackAccurately() {
        scenarioService.listUsersByAge(20);

        SlowSqlEvent event = alertProvider.getLatestEvent();
        assertThat(event).isNotNull();
        assertThat(event.getStatementId()).contains("UserMapper.selectList");
        assertThat(event.getSql()).contains("\n"); // 包含格式化换行缩进
        assertThat(event.getCallLocation())
                .isNotNull()
                .isNotEqualTo("UNKNOWN");
        assertThat(event.getCallLocation()).containsAnyOf(
                "SlowSqlAlertScenarioTest",
                "SlowSqlAlertScenarioServiceImpl"
        );
        // 精准断言包含代码行号定位
        assertThat(event.getCallLocation()).matches(".*:\\d+\\)?.*");
    }

    @Test
    @DisplayName("入库敏感加密字段在慢 SQL 告警参数列表中被安全掩码为 ****** 杜绝泄露")
    void shouldMaskSensitiveParametersInSlowSqlAlert() {
        String sensitiveEmail = "slowsql_test@example.com";
        String sensitivePhone = "13800138000";

        scenarioService.createUserWithEncryptedFields("SlowSqlUser", 28, sensitiveEmail, sensitivePhone, 100L);

        // 查找与 UserMapper.insert 对应的慢 SQL 告警事件
        SlowSqlEvent insertEvent = alertProvider.getEvents().stream()
                .filter(e -> e.getStatementId().contains("UserMapper.insert"))
                .findFirst()
                .orElse(null);

        assertThat(insertEvent).isNotNull();
        List<Object> parameters = insertEvent.getParameters();
        assertThat(parameters).isNotEmpty();

        // 验证参数列表中包含掩码字符串 ******
        assertThat(parameters).contains("******");

        // 严格断言明文敏感数据绝对未泄露在参数列表中
        assertThat(parameters).doesNotContain(sensitiveEmail);
        assertThat(parameters).doesNotContain(sensitivePhone);
    }

    @Test
    @DisplayName("SQL 绑定密码/凭据敏感参数在慢 SQL 告警参数列表中被安全掩码为 ****** 杜绝泄露")
    void shouldMaskPasswordParametersInSlowSqlAlert() {
        String sensitivePassword = "SuperSecretPassword123!";
        scenarioService.triggerSlowSqlWithPasswordParam("AdminUser", sensitivePassword);

        SlowSqlEvent event = alertProvider.getLatestEvent();
        assertThat(event).isNotNull();
        assertThat(event.getStatementId()).contains("UserMapper.selectByUsernameAndPassword");

        List<Object> parameters = event.getParameters();
        assertThat(parameters).isNotEmpty();
        assertThat(parameters).contains("******");
        assertThat(parameters).doesNotContain(sensitivePassword);
    }

    @Test
    @DisplayName("防御性单实体查询无记录时平滑返回 null 并正确触发慢 SQL 审计")
    void shouldHandleNonExistentRecordSafely() {
        UserDynamicVO nonExistent = scenarioService.getUserById(-99999L);
        assertThat(nonExistent).isNull();

        SlowSqlEvent event = alertProvider.getLatestEvent();
        assertThat(event).isNotNull();
        assertThat(event.getStatementId()).contains("UserMapper.selectById");
    }
}
