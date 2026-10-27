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
 * XSS 跨站脚本安全清洗与字段级富文本豁免场景端到端测试（ESCAPE 模式）
 * 验证 JSON 字符串属性转义、字段级与类级 @XssIgnore 豁免、Query/Form 参数清洗及白名单路径排除
 *
 * @author Ateng
 * @since 2026-10-27
 */
@SpringBootTest(properties = {
        "ateng.boot.web.xss.enabled=true",
        "ateng.boot.web.xss.mode=ESCAPE",
        "ateng.boot.web.xss.exclude-urls[0]=/api/sample/xss/excluded/**"
})
@AutoConfigureMockMvc
@DisplayName("XSS 安全清洗与字段级富文本豁免场景端到端测试 (ESCAPE 模式)")
class WebXssSampleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("1. 混合 DTO：普通字段转义 HTML 实体，@XssIgnore 标注的正文字段保留原始富文本")
    void shouldEscapeNormalFieldsAndPreserveFieldLevelXssIgnore() throws Exception {
        String json = "{\"title\":\"<script>alert('title')</script>\",\"content\":\"<p>Safe HTML</p><script>evil</script>\",\"author\":\"<b>admin</b>\"}";

        mockMvc.perform(post("/api/sample/xss/article")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.title").value("&lt;script&gt;alert(&#39;title&#39;)&lt;/script&gt;"))
                .andExpect(jsonPath("$.data.content").value("<p>Safe HTML</p><script>evil</script>"))
                .andExpect(jsonPath("$.data.author").value("&lt;b&gt;admin&lt;/b&gt;"));
    }

    @Test
    @DisplayName("2. 类级豁免：类上标注 @XssIgnore 时，实体内部所有 String 属性均跳过清洗")
    void shouldPreserveAllFieldsWhenClassAnnotatedWithXssIgnore() throws Exception {
        String json = "{\"title\":\"<h1>Announcement Title</h1>\",\"content\":\"<div>Announcement Body</div>\"}";

        mockMvc.perform(post("/api/sample/xss/announcement")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.title").value("<h1>Announcement Title</h1>"))
                .andExpect(jsonPath("$.data.content").value("<div>Announcement Body</div>"));
    }

    @Test
    @DisplayName("3. Query 参数清洗：XssHttpServletRequestWrapper 自动转义 GET 请求入参")
    void shouldCleanQueryParams() throws Exception {
        mockMvc.perform(get("/api/sample/xss/query")
                        .param("keyword", "<script>alert('query')</script>"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("&lt;script&gt;alert(&#39;query&#39;)&lt;/script&gt;"));
    }

    @Test
    @DisplayName("4. Form 表单参数清洗：XssHttpServletRequestWrapper 自动转义 POST 表单参数")
    void shouldCleanFormParams() throws Exception {
        mockMvc.perform(post("/api/sample/xss/form")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("title", "<script>alert('form')</script>")
                        .param("summary", "<b>summary</b>"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.title").value("&lt;script&gt;alert(&#39;form&#39;)&lt;/script&gt;"))
                .andExpect(jsonPath("$.data.summary").value("&lt;b&gt;summary&lt;/b&gt;"));
    }

    @Test
    @DisplayName("5. 路径白名单排除：命中的 URL 路径整体跳过 XSS 清洗")
    void shouldSkipXssCleaningWhenPathMatchesExcludeUrls() throws Exception {
        String json = "{\"title\":\"<script>not-cleaned</script>\",\"content\":\"<p>raw</p>\",\"author\":\"<b>raw</b>\"}";

        mockMvc.perform(post("/api/sample/xss/excluded/article")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.title").value("<script>not-cleaned</script>"))
                .andExpect(jsonPath("$.data.content").value("<p>raw</p>"))
                .andExpect(jsonPath("$.data.author").value("<b>raw</b>"));
    }
}
