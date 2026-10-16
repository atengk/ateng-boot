package io.github.atengk.boot.web.sign;

import io.github.atengk.boot.web.sign.util.SignCanonicalUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 待签串拼装工具单元测试
 *
 * @author Ateng
 * @since 2026-10-16
 */
@DisplayName("待签串拼装工具单元测试")
class SignCanonicalUtilTest {

    @Test
    @DisplayName("验证 Query 参数按字典序排序与多值排序")
    void testBuildSortedQueryString() {
        Map<String, String[]> params = new LinkedHashMap<>();
        params.put("z", new String[]{"last"});
        params.put("a", new String[]{"first"});
        params.put("m", new String[]{"v2", "v1"});

        String sortedQuery = SignCanonicalUtil.buildSortedQueryString(params);
        assertThat(sortedQuery).isEqualTo("a=first&m=v1&m=v2&z=last");

        assertThat(SignCanonicalUtil.buildSortedQueryString(null)).isEmpty();
        assertThat(SignCanonicalUtil.buildSortedQueryString(Map.of())).isEmpty();
    }

    @Test
    @DisplayName("验证入站待签串拼装规范")
    void testBuildRequestCanonicalString() {
        Map<String, String[]> params = new LinkedHashMap<>();
        params.put("name", new String[]{"ateng"});
        params.put("age", new String[]{"18"});

        String canonical = SignCanonicalUtil.buildRequestCanonicalString(
                "post",
                "/api/orders",
                "1729000000",
                "nonce123",
                params,
                "dummySha256"
        );

        String expected = "POST\n/api/orders\n1729000000\nnonce123\nage=18&name=ateng\ndummysha256";
        assertThat(canonical).isEqualTo(expected);
    }

    @Test
    @DisplayName("验证出站响应待签串拼装规范")
    void testBuildResponseCanonicalString() {
        String canonical = SignCanonicalUtil.buildResponseCanonicalString(
                "1729000000",
                "nonce123",
                "respBodySha256"
        );

        String expected = "1729000000\nnonce123\nrespbodysha256";
        assertThat(canonical).isEqualTo(expected);
    }
}
