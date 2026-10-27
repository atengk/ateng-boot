package io.github.atengk.boot.sample.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 安全响应头与 ETag 协商缓存场景端到端测试
 * 覆盖 OWASP ASVS & 等保三级基线安全响应头注入、Server 容器指纹剥离、containsHeader 保护及 ETag 304 协商缓存
 *
 * @author Ateng
 * @since 2026-10-27
 */
@SpringBootTest(properties = {
        "ateng.boot.web.etag.enabled=true"
})
@AutoConfigureMockMvc
@DisplayName("HTTP 安全响应头与 ETag 协商缓存场景端到端测试")
class WebSecurityHeadersAndEtagSampleTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("1. 安全响应头：自动注入等保三级基准安全响应头，并压制剥离 Server 容器特征")
    void shouldInjectDefaultSecurityHeadersAndSuppressServerHeader() throws Exception {
        mockMvc.perform(get("/api/sample/runtime/cached-dict"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"))
                .andExpect(header().string("X-XSS-Protection", "1; mode=block"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(header().doesNotExist("Server"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("2. 防覆盖保护：上游或控制器已设置特定安全头时，containsHeader 守卫保持用户自定义值")
    void shouldPreserveCustomSecurityHeaderWhenAlreadyPresent() throws Exception {
        mockMvc.perform(get("/api/sample/runtime/custom-security-headers"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-XSS-Protection", "1; mode=block"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("custom-headers-ok"));
    }

    @Test
    @DisplayName("3. ETag 协商缓存：首次请求生成 ETag，二次带 If-None-Match 请求命中返回 304 Not Modified")
    void shouldReturnEtagAndSupport304NotModifiedNegotiation() throws Exception {
        String fixedTrace = "fixed-trace-etag-001";

        // 1. 首次请求：期望返回 200，并生成响应头 ETag
        MvcResult firstResult = mockMvc.perform(get("/api/sample/runtime/cached-dict")
                        .header("X-Trace-Id", fixedTrace))
                .andExpect(status().isOk())
                .andExpect(header().exists("ETag"))
                .andReturn();

        String etag = firstResult.getResponse().getHeader("ETag");
        assertThat(etag).isNotBlank();
        assertThat(firstResult.getResponse().getContentAsString()).isNotEmpty();

        // 2. 二次请求：携带相同 If-None-Match 条件头，期望返回 304 Not Modified，且响应体为空
        MvcResult secondResult = mockMvc.perform(get("/api/sample/runtime/cached-dict")
                        .header("X-Trace-Id", fixedTrace)
                        .header("If-None-Match", etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", etag))
                .andReturn();

        assertThat(secondResult.getResponse().getContentAsString()).isEmpty();
    }

    @Test
    @DisplayName("4. ETag 协商缓存：携带不匹配的 If-None-Match 时正常返回 200 并包含最新响应内容")
    void shouldReturn200WhenEtagMismatched() throws Exception {
        String fixedTrace = "fixed-trace-etag-002";

        mockMvc.perform(get("/api/sample/runtime/cached-dict")
                        .header("X-Trace-Id", fixedTrace)
                        .header("If-None-Match", "\"mismatched-etag-value\""))
                .andExpect(status().isOk())
                .andExpect(header().exists("ETag"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }
}
