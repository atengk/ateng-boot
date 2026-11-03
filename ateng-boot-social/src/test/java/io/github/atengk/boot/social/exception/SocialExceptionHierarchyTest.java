package io.github.atengk.boot.social.exception;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.social.code.SocialResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 社交登录异常体系与因果链单元测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
class SocialExceptionHierarchyTest {

    @Test
    @DisplayName("1. SocialAuthException: 验证继承结构、错误码及异常因果链")
    void testSocialAuthException() {
        Throwable cause = new IllegalStateException("OAuth2 Handshake Timeout");
        SocialAuthException ex = new SocialAuthException("换码失败", cause);

        assertThat(ex).isInstanceOf(SocialException.class);
        assertThat(ex).isInstanceOf(BusinessException.class);
        assertThat(ex.getResultCode()).isEqualTo(SocialResultCode.SOCIAL_AUTH_FAILED);
        assertThat(ex.getMessage()).isEqualTo("换码失败");
        assertThat(ex.getCause()).isSameAs(cause);

        SocialAuthException exWithoutCause = new SocialAuthException("凭据解析失败");
        assertThat(exWithoutCause.getResultCode()).isEqualTo(SocialResultCode.SOCIAL_AUTH_FAILED);
        assertThat(exWithoutCause.getCause()).isNull();
    }

    @Test
    @DisplayName("2. SocialStateExpiredException: 验证防伪状态异常与结构化提示")
    void testSocialStateExpiredException() {
        SocialStateExpiredException ex = new SocialStateExpiredException("state_12345");

        assertThat(ex).isInstanceOf(SocialException.class);
        assertThat(ex.getResultCode()).isEqualTo(SocialResultCode.SOCIAL_STATE_INVALID);
        assertThat(ex.getMessage()).contains("state_12345").contains("非法、已过期或已被重复消费");

        Throwable cause = new IllegalArgumentException("State format illegal");
        SocialStateExpiredException exWithCause = new SocialStateExpiredException("自定义防伪异常说明", cause);
        assertThat(exWithCause.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("3. SocialUnbindRejectedException: 验证孤儿账号拦截异常与因果链")
    void testSocialUnbindRejectedException() {
        SocialUnbindRejectedException ex = new SocialUnbindRejectedException("user_999", "wechat_app");

        assertThat(ex).isInstanceOf(SocialException.class);
        assertThat(ex.getResultCode()).isEqualTo(SocialResultCode.SOCIAL_UNBIND_REJECTED);
        assertThat(ex.getMessage())
                .contains("user_999")
                .contains("wechat_app")
                .contains("未绑定手机号或未设独立密码，防止账号孤儿化");

        Throwable cause = new RuntimeException("Underlying security assertion failed");
        SocialUnbindRejectedException exWithCause = new SocialUnbindRejectedException("user_999", "wechat_app", cause);
        assertThat(exWithCause.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("4. SocialAppNotFoundException: 验证缺失应用配置异常")
    void testSocialAppNotFoundException() {
        SocialAppNotFoundException ex = new SocialAppNotFoundException("non_exist_app");

        assertThat(ex).isInstanceOf(SocialException.class);
        assertThat(ex.getResultCode()).isEqualTo(SocialResultCode.SOCIAL_APP_NOT_FOUND);
        assertThat(ex.getMessage()).contains("non_exist_app");
    }
}
