package io.github.atengk.boot.realtime.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 会话三维路由坐标单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
class SessionKeyTest {

    @Test
    void testStandardSessionKey() {
        SessionKey key = SessionKey.of(1001L, "pc", "tab-998");

        assertThat(key.getUserId()).isEqualTo("1001");
        assertThat(key.getDeviceType()).isEqualTo("pc");
        assertThat(key.getClientId()).isEqualTo("tab-998");
        assertThat(key.isAnonymous()).isFalse();
        assertThat(key.format()).isEqualTo("1001:pc:tab-998");
        assertThat(key.toString()).isEqualTo("1001:pc:tab-998");
    }

    @Test
    void testDefaultDeviceType() {
        SessionKey key = SessionKey.of("admin", "session-xyz");

        assertThat(key.getUserId()).isEqualTo("admin");
        assertThat(key.getDeviceType()).isEqualTo(SessionKey.DEFAULT_DEVICE_TYPE);
        assertThat(key.getClientId()).isEqualTo("session-xyz");
        assertThat(key.format()).isEqualTo("admin:default:session-xyz");
    }

    @Test
    void testAnonymousSessionKey() {
        SessionKey anon = SessionKey.anonymous("client-5566");

        assertThat(anon.isAnonymous()).isTrue();
        assertThat(anon.getUserId()).isEqualTo(SessionKey.ANONYMOUS_USER_PREFIX + "client-5566");
        assertThat(anon.getClientId()).isEqualTo("client-5566");
        assertThat(anon.format()).isEqualTo("anonymous:client-5566:default:client-5566");
    }

    @Test
    void testEqualityAndComparison() {
        SessionKey k1 = SessionKey.of("user-1", "web", "c-1");
        SessionKey k2 = SessionKey.of("user-1", "web", "c-1");
        SessionKey k3 = SessionKey.of("user-1", "mobile", "c-1");

        assertThat(k1).isEqualTo(k2);
        assertThat(k1.hashCode()).isEqualTo(k2.hashCode());
        assertThat(k1).isNotEqualTo(k3);
        assertThat(k1.compareTo(k2)).isZero();
        assertThat(k1.compareTo(k3)).isPositive();
    }

    @Test
    void testValidation() {
        assertThatThrownBy(() -> SessionKey.of(null, "tab-1"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SessionKey.of("1001", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testParse() {
        // 标准坐标双向往返解析
        SessionKey key1 = SessionKey.of("user-1001", "mobile", "client-99");
        String formatted1 = key1.format();
        SessionKey parsed1 = SessionKey.parse(formatted1);
        assertThat(parsed1).isEqualTo(key1);
        assertThat(parsed1.getUserId()).isEqualTo("user-1001");
        assertThat(parsed1.getDeviceType()).isEqualTo("mobile");
        assertThat(parsed1.getClientId()).isEqualTo("client-99");

        // 匿名用户 (userId 内部包含冒号) 解析
        SessionKey anon = SessionKey.anonymous("client-xyz");
        String formattedAnon = anon.format();
        SessionKey parsedAnon = SessionKey.parse(formattedAnon);
        assertThat(parsedAnon).isEqualTo(anon);
        assertThat(parsedAnon.getUserId()).isEqualTo("anonymous:client-xyz");
        assertThat(parsedAnon.getDeviceType()).isEqualTo("default");
        assertThat(parsedAnon.getClientId()).isEqualTo("client-xyz");

        // 非法入参异常防卫
        assertThatThrownBy(() -> SessionKey.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SessionKey.parse("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SessionKey.parse("invalid"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SessionKey.parse("invalid:part"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
