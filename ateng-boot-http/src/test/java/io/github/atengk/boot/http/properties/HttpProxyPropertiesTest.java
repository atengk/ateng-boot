package io.github.atengk.boot.http.properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HttpProxyProperties 正向代理与直连白名单单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class HttpProxyPropertiesTest {

    @Test
    @DisplayName("测试默认 nonProxyHosts 包含本地回环与局域网私有网段")
    void testDefaultNonProxyHosts() {
        HttpProxyProperties proxy = new HttpProxyProperties();

        assertThat(proxy.getNonProxyHosts()).contains(
                "localhost",
                "127.0.0.1",
                "10.*",
                "172.16.*",
                "192.168.*"
        );

        // 验证默认命中本地与私网
        assertThat(proxy.isNonProxyHost("localhost")).isTrue();
        assertThat(proxy.isNonProxyHost("127.0.0.1")).isTrue();
        assertThat(proxy.isNonProxyHost("10.0.0.1")).isTrue();
        assertThat(proxy.isNonProxyHost("10.254.12.34")).isTrue();
        assertThat(proxy.isNonProxyHost("172.16.0.1")).isTrue();
        assertThat(proxy.isNonProxyHost("192.168.1.100")).isTrue();

        // 验证外部公网主机不命中白名单（应通过代理路由）
        assertThat(proxy.isNonProxyHost("api.openai.com")).isFalse();
        assertThat(proxy.isNonProxyHost("172.32.0.1")).isFalse();
        assertThat(proxy.isNonProxyHost("8.8.8.8")).isFalse();
    }

    @Test
    @DisplayName("测试自定义域名通配符白名单匹配 (*.example.com 与前缀通配)")
    void testWildcardDomainMatching() {
        HttpProxyProperties proxy = new HttpProxyProperties();
        proxy.setNonProxyHosts(List.of("*.internal.net", "sso.*", "exact-host.com"));

        assertThat(proxy.isNonProxyHost("api.internal.net")).isTrue();
        assertThat(proxy.isNonProxyHost("db.internal.net")).isTrue();
        assertThat(proxy.isNonProxyHost("sso.corp.com")).isTrue();
        assertThat(proxy.isNonProxyHost("exact-host.com")).isTrue();

        // 未匹配域名
        assertThat(proxy.isNonProxyHost("external.net")).isFalse();
        assertThat(proxy.isNonProxyHost("internal.net")).isFalse(); // 必须是子域名 *.
        assertThat(proxy.isNonProxyHost("other.com")).isFalse();
    }

    @Test
    @DisplayName("空值与防御性校验")
    void testNullAndDefensiveChecks() {
        HttpProxyProperties proxy = new HttpProxyProperties();

        assertThat(proxy.isNonProxyHost(null)).isFalse();
        assertThat(proxy.isNonProxyHost("")).isFalse();
        assertThat(proxy.isNonProxyHost("   ")).isFalse();

        proxy.setNonProxyHosts(null);
        assertThat(proxy.isNonProxyHost("localhost")).isFalse();
    }
}
