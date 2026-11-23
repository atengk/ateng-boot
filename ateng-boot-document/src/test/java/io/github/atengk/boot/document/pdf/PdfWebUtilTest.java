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
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-cache, no-store, must-revalidate");

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
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-cache, no-store, must-revalidate");

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
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-cache, no-store, must-revalidate");

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
    @DisplayName("测试 write 便捷别名重载")
    void testWriteAliases() throws Exception {
        byte[] samplePdf = createSimplePdf();

        MockHttpServletResponse response1 = new MockHttpServletResponse();
        PdfWebUtil.write(response1, "报表下载", samplePdf);
        assertThat(response1.getStatus()).isEqualTo(200);
        assertThat(response1.getHeader(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment");

        MockHttpServletResponse response2 = new MockHttpServletResponse();
        PdfWebUtil.write(response2, "报表预览", samplePdf, true);
        assertThat(response2.getStatus()).isEqualTo(200);
        assertThat(response2.getHeader(HttpHeaders.CONTENT_DISPOSITION)).startsWith("inline");

        MockHttpServletResponse response3 = new MockHttpServletResponse();
        PdfWebUtil.write(response3, "函数写出", out -> out.write(samplePdf));
        assertThat(response3.getStatus()).isEqualTo(200);
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

    @Test
    @DisplayName("安全防护：文件名路径穿越与空字节清洗与拦截")
    void testFilenamePathTraversalSanitization() throws Exception {
        byte[] samplePdf = createSimplePdf();

        MockHttpServletResponse cleanedResp = new MockHttpServletResponse();
        PdfWebUtil.writeToResponse(cleanedResp, "../../evil/月度报告", samplePdf);
        String disposition = cleanedResp.getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).doesNotContain("..");
        assertThat(disposition).contains(".pdf");

        MockHttpServletResponse rejectedResp = new MockHttpServletResponse();
        assertThatThrownBy(() -> PdfWebUtil.writeToResponse(rejectedResp, "../../..", samplePdf))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PdfWebUtil.writeToResponse(rejectedResp, "\0", samplePdf))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("渲染图片浏览器流式输出：previewImage/downloadImage/writeImageToResponse MIME 自识别与安全头")
    void testPreviewAndDownloadImageStreaming() {
        byte[] fakePng = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 1, 2};
        byte[] fakeJpg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 3, 4};

        // 1. 默认无后缀时自动补齐 .png 与 image/png，inline 预览
        MockHttpServletResponse previewResp = new MockHttpServletResponse();
        PdfWebUtil.previewImage(previewResp, "contract_page_1", fakePng);
        assertThat(previewResp.getStatus()).isEqualTo(200);
        assertThat(previewResp.getContentType()).contains("image/png");
        assertThat(previewResp.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-cache, no-store, must-revalidate");
        assertThat(previewResp.getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .startsWith("inline")
                .contains(".png");
        assertThat(previewResp.getContentAsByteArray()).isEqualTo(fakePng);

        // 2. .jpg 文件名自动识别 image/jpeg，清洗路径穿越并保留单后缀，attachment 下载
        MockHttpServletResponse downloadResp = new MockHttpServletResponse();
        PdfWebUtil.downloadImage(downloadResp, "../../evil/photo.jpg", fakeJpg);
        assertThat(downloadResp.getStatus()).isEqualTo(200);
        assertThat(downloadResp.getContentType()).contains("image/jpeg");
        String disposition = downloadResp.getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition)
                .startsWith("attachment")
                .doesNotContain("..")
                .doesNotContain(".jpg.png")
                .contains(".jpg");
        assertThat(downloadResp.getContentAsByteArray()).isEqualTo(fakeJpg);

        // 3. 回调流式写入 writeImageToResponse
        MockHttpServletResponse writerResp = new MockHttpServletResponse();
        PdfWebUtil.writeImageToResponse(writerResp, "rendered.jpeg", out -> out.write(fakeJpg), true);
        assertThat(writerResp.getContentType()).contains("image/jpeg");
        assertThat(writerResp.getHeader(HttpHeaders.CONTENT_DISPOSITION)).startsWith("inline");
        assertThat(writerResp.getContentAsByteArray()).isEqualTo(fakeJpg);
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
