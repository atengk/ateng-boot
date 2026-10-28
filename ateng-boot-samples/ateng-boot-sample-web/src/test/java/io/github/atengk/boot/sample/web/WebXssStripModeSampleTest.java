package io.github.atengk.boot.sample.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * XSS 跨站脚本安全清洗场景端到端测试（STRIP 标签剔除模式）
 * 验证 HTML 标签与恶意 script 代码块剔除、普通文本保留与 @XssIgnore 豁免放行
 *
 * @author Ateng
 * @since 2026-10-27
 */
@SpringBootTest(properties = {
        "ateng.boot.web.xss.enabled=true",
        "ateng.boot.web.xss.mode=STRIP"
})
@AutoConfigureMockMvc
@DisplayName("XSS 安全清洗场景端到端测试 (STRIP 模式)")
class WebXssStripModeSampleTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("1. STRIP 模式：普通字段剔除 HTML 标签与 script 脚本，@XssIgnore 字段保留原样富文本")
    void shouldStripNormalFieldAndPreserveXssIgnoreField() throws Exception {
        String json = "{\"title\":\"<b>Clean Title</b><script>alert(1)</script>\",\"content\":\"<h1>Rich Body</h1>\",\"author\":\"<img src=x onerror=alert(1)>admin\"}";

        mockMvc.perform(post("/api/sample/xss/article")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.title").value("Clean Title"))
                .andExpect(jsonPath("$.data.content").value("<h1>Rich Body</h1>"))
                .andExpect(jsonPath("$.data.author").value("admin"));
    }

    @Test
    @DisplayName("2. STRIP 模式：Query 搜索参数中 HTML 标签与恶意脚本被彻底剔除")
    void shouldStripQueryParams() throws Exception {
        mockMvc.perform(get("/api/sample/xss/query")
                        .param("keyword", "<script>alert('x')</script>keyword-only"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("keyword-only"));
    }
}
