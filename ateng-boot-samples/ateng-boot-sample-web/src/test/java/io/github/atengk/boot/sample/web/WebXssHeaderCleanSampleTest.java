package io.github.atengk.boot.sample.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * XSS 请求头安全清洗端到端集成测试 (cleanHeaders = true)
 *
 * @author Ateng
 * @since 2026-11-09
 */
@SpringBootTest(properties = {
        "ateng.boot.web.xss.enabled=true",
        "ateng.boot.web.xss.mode=ESCAPE",
        "ateng.boot.web.xss.clean-headers=true"
})
@AutoConfigureMockMvc
@DisplayName("XSS 请求头清洗端到端集成测试")
class WebXssHeaderCleanSampleTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("当 cleanHeaders=true 时，未排除请求头被无害化转义，白名单排除请求头原样保留")
    void shouldCleanHeadersWhenConfigured() throws Exception {
        mockMvc.perform(get("/sample/web/security/xss/header")
                        .header("X-Custom-Title", "<script>alert('header')</script>")
                        .header("Authorization", "Bearer <secret-key>"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.X-Custom-Title").value("&lt;script&gt;alert(&#39;header&#39;)&lt;/script&gt;"))
                .andExpect(jsonPath("$.data.Authorization").value("Bearer <secret-key>"));
    }
}
