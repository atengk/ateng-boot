package io.github.atengk.boot.satoken.defense;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.SaTokenContextDefaultImpl;
import cn.dev33.satoken.exception.DisableServiceException;
import io.github.atengk.boot.satoken.properties.SaTokenProperties;
import io.github.atengk.boot.satoken.util.LoginHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LoginFailureLockService 多租户与多账号体系隔离重载单元测试 (#1079)
 *
 * @author Ateng
 * @since 2026-11-19
 */
@DisplayName("LoginFailureLockService 多租户与多账号隔离重载单元测试")
class LoginFailureLockServiceScopedTest {

    private LoginFailureLockService service;

    @BeforeEach
    void setUp() {
        SaTokenProperties properties = new SaTokenProperties();
        properties.getLoginDefense().setEnabled(true);
        properties.getLoginDefense().setMaxRetryCount(3);
        properties.getLoginDefense().setLockTime(Duration.ofMinutes(10));
        properties.getLoginDefense().setWindowTime(Duration.ofMinutes(10));
        properties.getLoginDefense().setIpMaxRetryCount(30);
        properties.getLoginDefense().setIpWhiteList(List.of("127.0.0.1"));
        service = new LoginFailureLockService(null, properties);
    }

    @AfterEach
    void tearDown() {
        cleanupScoped("tenant-a", "admin", "zhangsan");
        cleanupScoped("tenant-b", "admin", "zhangsan");
        cleanupScoped("tenant-a", "member", "zhangsan");
        cleanupScoped("a:b", "login", "c");
        cleanupScoped("a", "login", "b:c");
        cleanupScoped("", "login", "zhangsan");
        cleanupScoped("0", "login", "zhangsan");
        try {
            LoginHelper.untieDisable("zhangsan");
        } catch (Exception ignored) {
        }
        service.clearLocalMemory();
        SaManager.setSaTokenContext(SaTokenContextDefaultImpl.defaultContext);
    }

    private void cleanupScoped(String tenantId, String loginType, String account) {
        try {
            String key = service.buildScopedAccountKey(tenantId, loginType, account);
            String resolvedType = (loginType == null || loginType.isBlank()) ? "login" : loginType.trim();
            LoginHelper.untieDisableByLoginType(resolvedType, key);
        } catch (Exception ignored) {
        }
    }

    @Test
    @DisplayName("1. 跨租户同名账号与同租户跨 loginType 严格隔离，全部 9 组对称重载方法行为一致")
    void shouldIsolateAcrossTenantsAndLoginTypesWithFullSymmetricOverloads() {
        // 租户 A admin/zhangsan 连续失败 3 次触发锁定
        assertThat(service.recordFailure("tenant-a", "admin", "zhangsan")).isEqualTo(2);
        assertThat(service.getFailureCount("tenant-a", "admin", "zhangsan")).isEqualTo(1);
        assertThat(service.getRemainRetryCount("tenant-a", "admin", "zhangsan")).isEqualTo(2);

        assertThat(service.recordFailure("tenant-a", "admin", "zhangsan", "127.0.0.1")).isEqualTo(1);
        assertThat(service.recordFailure("tenant-a", "admin", "zhangsan", "127.0.0.1")).isEqualTo(0);

        // 租户 A admin/zhangsan 已被锁定，查询与 checkLocked 均生效
        assertThat(service.isLocked("tenant-a", "admin", "zhangsan")).isTrue();
        assertThat(service.isLocked("tenant-a", "admin", "zhangsan", "127.0.0.1")).isTrue();
        assertThat(service.getDisableTime("tenant-a", "admin", "zhangsan")).isGreaterThan(0L);
        assertThat(service.getLockTime("tenant-a", "admin", "zhangsan")).isGreaterThan(0L);
        assertThat(service.getRemainRetryCount("tenant-a", "admin", "zhangsan")).isEqualTo(0);
        assertThat(service.getFailureCount("tenant-a", "admin", "zhangsan")).isEqualTo(3);
        assertThatThrownBy(() -> service.checkLocked("tenant-a", "admin", "zhangsan"))
                .isInstanceOf(DisableServiceException.class);
        assertThatThrownBy(() -> service.checkLocked("tenant-a", "admin", "zhangsan", "127.0.0.1"))
                .isInstanceOf(DisableServiceException.class);

        // 租户 B 同名 admin/zhangsan 完全不受影响
        assertThat(service.isLocked("tenant-b", "admin", "zhangsan")).isFalse();
        assertThat(service.getFailureCount("tenant-b", "admin", "zhangsan")).isEqualTo(0);
        assertThat(service.getRemainRetryCount("tenant-b", "admin", "zhangsan")).isEqualTo(3);
        assertThat(service.getDisableTime("tenant-b", "admin", "zhangsan")).isEqualTo(0L);
        service.checkLocked("tenant-b", "admin", "zhangsan");

        // 同租户 A 下的 member/zhangsan 完全不受影响
        assertThat(service.isLocked("tenant-a", "member", "zhangsan")).isFalse();
        assertThat(service.getFailureCount("tenant-a", "member", "zhangsan")).isEqualTo(0);
        service.checkLocked("tenant-a", "member", "zhangsan");

        // 旧单 account 方法语义与隔离键互不串染
        assertThat(service.isLocked("zhangsan")).isFalse();
        assertThat(service.getFailureCount("zhangsan")).isEqualTo(0);

        // 清理测试：clearFailure 与 clearAllFailure 对称清理各自作用域计数
        service.recordFailure("tenant-b", "admin", "zhangsan", "198.51.100.88");
        assertThat(service.getFailureCount("tenant-b", "admin", "zhangsan")).isEqualTo(1);
        service.clearFailure("tenant-b", "admin", "zhangsan");
        assertThat(service.getFailureCount("tenant-b", "admin", "zhangsan")).isEqualTo(0);
        assertThat(service.getIpFailureCount("198.51.100.88")).isEqualTo(1);

        service.recordFailure("tenant-b", "admin", "zhangsan", "198.51.100.88");
        service.clearAllFailure("tenant-b", "admin", "zhangsan", "198.51.100.88");
        assertThat(service.getFailureCount("tenant-b", "admin", "zhangsan")).isEqualTo(0);
        assertThat(service.getIpFailureCount("198.51.100.88")).isEqualTo(0);
    }

    @Test
    @DisplayName("2. 防碰撞定界编码：含冒号、竖线与空租户等边界值绝不碰撞")
    void shouldPreventKeyCollisionForSpecialDelimiterCharactersAndEmptyTenant() {
        String k1 = service.buildScopedAccountKey("a:b", "login", "c");
        String k2 = service.buildScopedAccountKey("a", "login", "b:c");
        String k3 = service.buildScopedAccountKey("a|b", "c", "d");
        String k4 = service.buildScopedAccountKey("a", "b|c", "d");
        String k5 = service.buildScopedAccountKey("", "login", "zhangsan");
        String k6 = service.buildScopedAccountKey("0", "login", "zhangsan");
        String k7 = service.buildScopedAccountKey(null, null, "zhangsan");

        assertThat(k1).isNotEqualTo(k2);
        assertThat(k3).isNotEqualTo(k4);
        assertThat(k5).isNotEqualTo(k6);
        assertThat(k5).isEqualTo(k7);

        // 触发 ("a:b", "login", "c") 锁定，不影响 ("a", "login", "b:c")
        for (int i = 0; i < 3; i++) {
            service.recordFailure("a:b", "login", "c", "127.0.0.1");
        }
        assertThat(service.isLocked("a:b", "login", "c")).isTrue();
        assertThat(service.isLocked("a", "login", "b:c")).isFalse();
        service.checkLocked("a", "login", "b:c");

        // 空 account 防卫不抛异常
        assertThat(service.getFailureCount("tenant-a", "admin", "")).isEqualTo(0);
        assertThat(service.isLocked("tenant-a", "admin", null)).isFalse();
        assertThat(service.getDisableTime("tenant-a", "admin", "  ")).isEqualTo(0L);
        assertThat(service.getRemainRetryCount("tenant-a", "admin", null)).isEqualTo(0);
    }
}
