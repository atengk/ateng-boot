package io.github.atengk.boot.document.word;

import io.github.atengk.boot.document.word.util.WordWebUtil;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WordWebUtil Web 浏览器流式安全导出单测用例
 *
 * @author Ateng
 * @since 2026-10-23
 */
class WordWebUtilTest {

    @Test
    @DisplayName("测试 Word 模板渲染并流式输出至 HttpServletResponse：验证 RFC 6266 标头与内容")
    void testRenderAndWriteToResponse() throws Exception {
        byte[] templateDocx = createSimpleTemplateDocx("尊敬的客户 {{customerName}}，您的订单 {{orderNo}} 已审核通过。");

        MockHttpServletResponse response = new MockHttpServletResponse();
        Map<String, Object> data = Map.of(
                "customerName", "张三科技",
                "orderNo", "ORD-20261023-888"
        );

        WordWebUtil.writeToResponse(response, "客户审批确认单", templateDocx, data);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).contains(WordWebUtil.DOCX_CONTENT_TYPE);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-cache, no-store, must-revalidate");

        String disposition = response.getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).isNotNull();
        assertThat(disposition).startsWith("attachment");
        assertThat(disposition).contains("filename=");
        assertThat(disposition).contains("filename*=");
        assertThat(disposition).contains(".docx");

        // 验证输出流中的 docx 内容
        byte[] contentBytes = response.getContentAsByteArray();
        assertThat(contentBytes).isNotEmpty();

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(contentBytes))) {
            String fullText = doc.getParagraphs().stream()
                    .map(XWPFParagraph::getText)
                    .reduce("", (a, b) -> a + b);
            assertThat(fullText).contains("张三科技");
            assertThat(fullText).contains("ORD-20261023-888");
        }
    }

    @Test
    @DisplayName("测试直接流式输出已有 Word 文档至 HttpServletResponse")
    void testWriteRawDocxToResponse() throws Exception {
        byte[] sampleDocx = createSimpleTemplateDocx("已有固定静态文档内容");

        MockHttpServletResponse response = new MockHttpServletResponse();
        WordWebUtil.writeToResponse(response, "静态归档.docx", sampleDocx);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).contains(WordWebUtil.DOCX_CONTENT_TYPE);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-cache, no-store, must-revalidate");

        String disposition = response.getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).contains("attachment");
        assertThat(disposition).contains("filename*=");

        byte[] contentBytes = response.getContentAsByteArray();
        assertThat(contentBytes).isEqualTo(sampleDocx);
    }

    @Test
    @DisplayName("测试 WordWebWriter 函数式写入与便捷别名（write / download）")
    void testWordWebWriterAndAliases() throws Exception {
        byte[] sampleDocx = createSimpleTemplateDocx("函数式写入内容");

        MockHttpServletResponse response = new MockHttpServletResponse();
        WordWebUtil.write(response, "函数直写", out -> out.write(sampleDocx));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).contains(WordWebUtil.DOCX_CONTENT_TYPE);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-cache, no-store, must-revalidate");
        assertThat(response.getContentAsByteArray()).isEqualTo(sampleDocx);

        MockHttpServletResponse downloadResponse = new MockHttpServletResponse();
        WordWebUtil.download(downloadResponse, "附件下载", sampleDocx);
        assertThat(downloadResponse.getStatus()).isEqualTo(200);
        assertThat(downloadResponse.getContentAsByteArray()).isEqualTo(sampleDocx);
    }

    @Test
    @DisplayName("测试防御性参数校验：response 或 filename 为空时抛出异常")
    void testDefensiveValidation() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        byte[] dummyDocx = new byte[]{1, 2, 3};

        assertThatThrownBy(() -> WordWebUtil.writeToResponse(null, "test.docx", dummyDocx))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("response 不能为空");

        assertThatThrownBy(() -> WordWebUtil.writeToResponse(response, "", dummyDocx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("filename 不能为空");
    }

    private static byte[] createSimpleTemplateDocx(String text) throws IOException {
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph p = doc.createParagraph();
            p.createRun().setText(text);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.write(baos);
            return baos.toByteArray();
        }
    }
}
