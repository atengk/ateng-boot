package io.github.atengk.boot.social.support;

import io.github.atengk.boot.social.code.SocialResultCode;
import io.github.atengk.boot.social.exception.SocialRedirectUriInvalidException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 社交登录重定向地址安全校验器单元测试
 *
 * @author Ateng
 * @since 2026-11-07
 */
class SocialRedirectUriValidatorTest {

    @Test
    @DisplayName("1. 空地址与空白字符串放行")
    void testBlankAndNull() {
        assertThatCode(() -> SocialRedirectUriValidator.validate(null, List.of("example.com"))).doesNotThrowAnyException();
        assertThatCode(() -> SocialRedirectUriValidator.validate("", List.of("example.com"))).doesNotThrowAnyException();
        assertThatCode(() -> SocialRedirectUriValidator.validate("   ", List.of("example.com"))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://example.com\r\nSet-Cookie:bad",
            "http://example.com\nEvil:1",
            "http://example.com\t/path"
    })
    @DisplayName("2. 拦截控制字符与 CRLF 注入")
    void testControlCharacters(String maliciousUrl) {
        assertThatThrownBy(() -> SocialRedirectUriValidator.validate(maliciousUrl, null))
                .isInstanceOf(SocialRedirectUriInvalidException.class)
                .hasMessageContaining("包含非法控制字符");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)",
            "JAVASCRIPT:alert(document.cookie)",
            "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==",
            "vbscript:msgbox(1)"
    })
    @DisplayName("3. 拦截危险伪协议")
    void testDangerousProtocols(String maliciousUrl) {
        assertThatThrownBy(() -> SocialRedirectUriValidator.validate(maliciousUrl, null))
                .isInstanceOf(SocialRedirectUriInvalidException.class)
                .hasMessageContaining("包含危险伪协议");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "//evil.com/phishing",
            "/\\evil.com",
            "\\\\evil.com",
            "\\evil.com"
    })
    @DisplayName("4. 拦截协议相对路径与异常反斜杠")
    void testProtocolRelativePaths(String maliciousUrl) {
        assertThatThrownBy(() -> SocialRedirectUriValidator.validate(maliciousUrl, null))
                .isInstanceOf(SocialRedirectUriInvalidException.class)
                .hasMessageContaining("协议相对路径");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/",
            "/home",
            "/user/profile",
            "/oauth/callback?redirect=success&tenantId=dev"
    })
    @DisplayName("5. 单斜杠站内相对路径安全放行")
    void testSafeRelativePaths(String relativeUrl) {
        assertThatCode(() -> SocialRedirectUriValidator.validate(relativeUrl, List.of("example.com")))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://example.com/file",
            "file:///etc/passwd",
            "gopher://evil.com"
    })
    @DisplayName("6. 拦截非 http/https 协议")
    void testNonHttpProtocols(String invalidSchemeUrl) {
        assertThatThrownBy(() -> SocialRedirectUriValidator.validate(invalidSchemeUrl, null))
                .isInstanceOf(SocialRedirectUriInvalidException.class)
                .hasMessageContaining("必须使用 http 或 https 协议");
    }

    @Test
    @DisplayName("7. 白名单为空时允许任意合法 http/https 绝对地址")
    void testEmptyWhitelistAllowsAnyHttp() {
        assertThatCode(() -> SocialRedirectUriValidator.validate("https://google.com/search", null))
                .doesNotThrowAnyException();
        assertThatCode(() -> SocialRedirectUriValidator.validate("http://any-domain.org:8080/cb", List.of()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("8. 精确域名白名单校验")
    void testExactDomainWhitelist() {
        List<String> whitelist = List.of("example.com", "ateng.github.io");

        assertThatCode(() -> SocialRedirectUriValidator.validate("https://example.com/welcome", whitelist))
                .doesNotThrowAnyException();
        assertThatCode(() -> SocialRedirectUriValidator.validate("http://ateng.github.io/blog", whitelist))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> SocialRedirectUriValidator.validate("https://evil.com/login", whitelist))
                .isInstanceOf(SocialRedirectUriInvalidException.class)
                .satisfies(e -> {
                    SocialRedirectUriInvalidException ex = (SocialRedirectUriInvalidException) e;
                    assertThat(ex.getResultCode()).isEqualTo(SocialResultCode.SOCIAL_REDIRECT_URI_INVALID);
                })
                .hasMessageContaining("不在允许的白名单列表中");

        // 精确匹配时不匹配子域名
        assertThatThrownBy(() -> SocialRedirectUriValidator.validate("https://sub.example.com/login", whitelist))
                .isInstanceOf(SocialRedirectUriInvalidException.class)
                .hasMessageContaining("不在允许的白名单列表中");
    }

    @Test
    @DisplayName("9. 泛域名白名单校验 (*.example.com)")
    void testWildcardDomainWhitelist() {
        List<String> whitelist = List.of("*.example.com");

        // 根域名与各级子域名均匹配
        assertThatCode(() -> SocialRedirectUriValidator.validate("https://example.com/cb", whitelist))
                .doesNotThrowAnyException();
        assertThatCode(() -> SocialRedirectUriValidator.validate("https://auth.example.com/cb", whitelist))
                .doesNotThrowAnyException();
        assertThatCode(() -> SocialRedirectUriValidator.validate("https://deep.sub.example.com/cb", whitelist))
                .doesNotThrowAnyException();

        // 相似仿冒域名阻断
        assertThatThrownBy(() -> SocialRedirectUriValidator.validate("https://attacker-example.com/cb", whitelist))
                .isInstanceOf(SocialRedirectUriInvalidException.class)
                .hasMessageContaining("不在允许的白名单列表中");
        assertThatThrownBy(() -> SocialRedirectUriValidator.validate("https://example.org/cb", whitelist))
                .isInstanceOf(SocialRedirectUriInvalidException.class)
                .hasMessageContaining("不在允许的白名单列表中");
    }
}
