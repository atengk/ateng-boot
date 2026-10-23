package io.github.atengk.boot.document.pdf;

import io.github.atengk.boot.document.pdf.util.PdfWebUtil;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PdfWebUtil Web 浏览器流式安全输出与预览单测用例
 *
 * @author Ateng
 * @since 2026-10-23
 */
class PdfWebUtilTest {

    @Test
    @DisplayName("测试 PDF 附件直接下载模式（attachment）：验证 RFC 6266 标头与内容")
    void testWriteToResponseAttachment() throws Exception {
        byte[] samplePdf = createSimplePdf();

        MockHttpServletResponse response = new MockHttpServletResponse();
        PdfWebUtil.writeToResponse(response, "月度财务报表", samplePdf);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).contains(PdfWebUtil.PDF_CONTENT_TYPE);

        String disposition = response.getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).isNotNull();
        assertThat(disposition).startsWith("attachment");
        assertThat(disposition).contains("filename=");
        assertThat(disposition).contains("filename*=");
        assertThat(disposition).contains(".pdf");

        byte[] contentBytes = response.getContentAsByteArray();
        assertThat(contentBytes).isEqualTo(samplePdf);
    }

    @Test
    @DisplayName("测试 PDF 浏览器内联预览模式（inline）：验证 RFC 6266 标头与内容")
    void testWriteToResponseInline() throws Exception {
        byte[] samplePdf = createSimplePdf();

        MockHttpServletResponse response = new MockHttpServletResponse();
        PdfWebUtil.writeToResponse(response, "在线合同预览.pdf", samplePdf, true);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).contains(PdfWebUtil.PDF_CONTENT_TYPE);

        String disposition = response.getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).isNotNull();
        assertThat(disposition).startsWith("inline");
        assertThat(disposition).contains("filename*=");

        byte[] contentBytes = response.getContentAsByteArray();
        assertThat(contentBytes).isEqualTo(samplePdf);
    }

    @Test
    @DisplayName("测试回调写入动作 PdfWebWriter：验证流式输出与内联预览便捷方法")
    void testPreviewWithWriter() throws Exception {
        byte[] samplePdf = createSimplePdf();

        MockHttpServletResponse response = new MockHttpServletResponse();
        PdfWebUtil.preview(response, "即时生成报告", out -> out.write(samplePdf));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).contains(PdfWebUtil.PDF_CONTENT_TYPE);

        String disposition = response.getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).startsWith("inline");

        byte[] contentBytes = response.getContentAsByteArray();
        try (PDDocument doc = Loader.loadPDF(contentBytes)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("测试下载便捷方法 download")
    void testDownloadWithWriter() throws Exception {
        byte[] samplePdf = createSimplePdf();

        MockHttpServletResponse response = new MockHttpServletResponse();
        PdfWebUtil.download(response, "归档下载", out -> out.write(samplePdf));

        assertThat(response.getStatus()).isEqualTo(200);
        String disposition = response.getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).startsWith("attachment");
    }

    @Test
    @DisplayName("测试防御性参数校验：response 或 filename 为空时抛出异常")
    void testDefensiveValidation() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        byte[] dummyPdf = new byte[]{1, 2, 3};

        assertThatThrownBy(() -> PdfWebUtil.writeToResponse(null, "test.pdf", dummyPdf))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("response 不能为空");

        assertThatThrownBy(() -> PdfWebUtil.writeToResponse(response, "  ", dummyPdf))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("filename 不能为空");
    }

    private static byte[] createSimplePdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.A4));
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }
}
