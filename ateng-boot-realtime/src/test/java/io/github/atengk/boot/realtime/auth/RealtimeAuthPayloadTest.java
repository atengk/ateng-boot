package io.github.atengk.boot.realtime.auth;

import io.github.atengk.boot.realtime.model.SessionKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 实时通信握手身份载荷单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
@DisplayName("RealtimeAuthPayload 身份载荷测试")
class RealtimeAuthPayloadTest {

    @Test
    @DisplayName("构建标准已认证用户载荷")
    void testStandardAuthPayload() {
        RealtimeAuthPayload payload = RealtimeAuthPayload.of("user-100", "pc", 888L);

        assertThat(payload.getUserId()).isEqualTo("user-100");
        assertThat(payload.getDeviceType()).isEqualTo("pc");
        assertThat(payload.getTenantId()).isEqualTo(888L);
        assertThat(payload.isAnonymous()).isFalse();
        assertThat(payload.getExtraAttributes()).isEmpty();

        // 仅传 userId
        RealtimeAuthPayload simplePayload = RealtimeAuthPayload.of(12345L);
        assertThat(simplePayload.getUserId()).isEqualTo("12345");
        assertThat(simplePayload.getDeviceType()).isEqualTo(SessionKey.DEFAULT_DEVICE_TYPE);
        assertThat(simplePayload.getTenantId()).isNull();
        assertThat(simplePayload.isAnonymous()).isFalse();

        // null userId 抛出 NPE
        assertThatThrownBy(() -> RealtimeAuthPayload.of(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("构建匿名访客身份载荷")
    void testAnonymousPayload() {
        RealtimeAuthPayload payload = RealtimeAuthPayload.anonymous("client-xyz");

        assertThat(payload.getUserId()).isEqualTo(SessionKey.ANONYMOUS_USER_PREFIX + "client-xyz");
        assertThat(payload.getDeviceType()).isEqualTo(SessionKey.DEFAULT_DEVICE_TYPE);
        assertThat(payload.getTenantId()).isNull();
        assertThat(payload.isAnonymous()).isTrue();

        // null clientId 时提供保底值
        RealtimeAuthPayload fallbackAnon = RealtimeAuthPayload.anonymous(null);
        assertThat(fallbackAnon.getUserId()).isEqualTo(SessionKey.ANONYMOUS_USER_PREFIX + "anon");
        assertThat(fallbackAnon.isAnonymous()).isTrue();
    }

    @Test
    @DisplayName("通过 Builder 构建扩展属性身份载荷")
    void testBuilderWithExtraAttributes() {
        RealtimeAuthPayload payload = RealtimeAuthPayload.builder()
                .userId("u-999")
                .deviceType("mobile")
                .tenantId(777L)
                .anonymous(false)
                .extraAttributes(Map.of("role", "admin", "deptId", 10))
                .build();

        assertThat(payload.getUserId()).isEqualTo("u-999");
        assertThat(payload.getDeviceType()).isEqualTo("mobile");
        assertThat(payload.getTenantId()).isEqualTo(777L);
        assertThat(payload.getExtraAttributes()).containsEntry("role", "admin").containsEntry("deptId", 10);
    }
}
