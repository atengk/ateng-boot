package io.github.atengk.boot.web.xss;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.github.atengk.boot.web.xss.annotation.XssIgnore;
import lombok.Data;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Jackson XSS 字符串反序列化器单元测试
 * 覆盖字段级 @XssIgnore 豁免、类级别 @XssIgnore 豁免、嵌套实体处理与双轨清洗模式
 *
 * @author Ateng
 * @since 2026-10-27
 */
@DisplayName("XssStringDeserializer 反序列化器单元测试")
class XssStringDeserializerTest {

    private ObjectMapper escapeMapper;
    private ObjectMapper stripMapper;

    @BeforeEach
    void setUp() {
        escapeMapper = new ObjectMapper();
        SimpleModule escapeModule = new SimpleModule();
        escapeModule.addDeserializer(String.class, new XssStringDeserializer(new XssCleaner(XssMode.ESCAPE)));
        escapeMapper.registerModule(escapeModule);

        stripMapper = new ObjectMapper();
        SimpleModule stripModule = new SimpleModule();
        stripModule.addDeserializer(String.class, new XssStringDeserializer(new XssCleaner(XssMode.STRIP)));
        stripMapper.registerModule(stripModule);
    }

    @Data
    public static class ArticleDTO {
        private String title;

        @XssIgnore
        private String content;
    }

    @Data
    @XssIgnore
    public static class AnnouncementDTO {
        private String title;
        private String content;
    }

    @Data
    public static class NestedOuterDTO {
        private String category;
        private ArticleDTO article;
    }

    @Test
    @DisplayName("1. 字段级豁免（ESCAPE 模式）：普通字段转义 HTML 实体，@XssIgnore 字段保留原始富文本")
    void shouldEscapeNormalFieldAndPreserveIgnoredField() throws Exception {
        String json = "{\"title\":\"<script>alert('title')</script>\",\"content\":\"<h1>Rich Article</h1><script>inner</script>\"}";

        ArticleDTO dto = escapeMapper.readValue(json, ArticleDTO.class);

        assertThat(dto.getTitle()).isEqualTo("&lt;script&gt;alert(&#39;title&#39;)&lt;/script&gt;");
        assertThat(dto.getContent()).isEqualTo("<h1>Rich Article</h1><script>inner</script>");
    }

    @Test
    @DisplayName("2. 字段级豁免（STRIP 模式）：普通字段剔除 HTML 标签，@XssIgnore 字段保留原始富文本")
    void shouldStripNormalFieldAndPreserveIgnoredField() throws Exception {
        String json = "{\"title\":\"<b>Bold Title</b><script>evil</script>\",\"content\":\"<p>Article body</p>\"}";

        ArticleDTO dto = stripMapper.readValue(json, ArticleDTO.class);

        assertThat(dto.getTitle()).isEqualTo("Bold Title");
        assertThat(dto.getContent()).isEqualTo("<p>Article body</p>");
    }

    @Test
    @DisplayName("3. 类级豁免：类上标注 @XssIgnore 时，实体内部所有 String 属性均跳过清洗")
    void shouldPreserveAllFieldsWhenClassAnnotatedWithXssIgnore() throws Exception {
        String json = "{\"title\":\"<script>title</script>\",\"content\":\"<p>full html</p>\"}";

        AnnouncementDTO dto = escapeMapper.readValue(json, AnnouncementDTO.class);

        assertThat(dto.getTitle()).isEqualTo("<script>title</script>");
        assertThat(dto.getContent()).isEqualTo("<p>full html</p>");
    }

    @Test
    @DisplayName("4. 嵌套实体支持：正确穿透并解析嵌套对象内的 @XssIgnore 属性")
    void shouldHandleNestedDtoWithXssIgnore() throws Exception {
        String json = "{\"category\":\"<script>cat</script>\",\"article\":{\"title\":\"<b>t</b>\",\"content\":\"<script>raw</script>\"}}";

        NestedOuterDTO dto = escapeMapper.readValue(json, NestedOuterDTO.class);

        assertThat(dto.getCategory()).isEqualTo("&lt;script&gt;cat&lt;/script&gt;");
        assertThat(dto.getArticle().getTitle()).isEqualTo("&lt;b&gt;t&lt;/b&gt;");
        assertThat(dto.getArticle().getContent()).isEqualTo("<script>raw</script>");
    }

    @Test
    @DisplayName("5. 空安全：null 值正确反序列化为 null")
    void shouldHandleNullValue() throws Exception {
        String json = "{\"title\":null,\"content\":null}";

        ArticleDTO dto = escapeMapper.readValue(json, ArticleDTO.class);

        assertThat(dto.getTitle()).isNull();
        assertThat(dto.getContent()).isNull();
    }
}
