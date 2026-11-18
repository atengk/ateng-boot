package io.github.atengk.boot.document.spi;

import io.github.atengk.boot.document.exception.DocumentException;
import io.github.atengk.boot.document.exception.DocumentResultCode;
import io.github.atengk.boot.document.properties.DocumentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Gotenberg 文档转换适配器单元测试
 *
 * @author Ateng
 * @since 2026-11-18
 */
@DisplayName("Gotenberg 文档跨格式转换适配器单元测试")
class GotenbergDocumentConverterTest {

    @Test
    @DisplayName("成功将 DOCX 与 HTML 流提交至 Gotenberg 多部分端点并返回 PDF 字节")
    void testDocxAndHtmlConversionSuccess() {
        DocumentProperties properties = createValidProperties("http://gotenberg.internal:3000/");
        RestClient.Builder builder = RestClient.builder().baseUrl("http://gotenberg.internal:3000");
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        GotenbergDocumentConverter converter = new GotenbergDocumentConverter(properties, builder.build());

        byte[] expectedPdf = "%PDF-1.7-converted".getBytes(StandardCharsets.UTF_8);

        mockServer.expect(requestTo("http://gotenberg.internal:3000/forms/libreoffice/convert"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, startsWith("multipart/form-data")))
                .andRespond(withSuccess(expectedPdf, MediaType.APPLICATION_PDF));

        mockServer.expect(requestTo("http://gotenberg.internal:3000/forms/chromium/convert/html"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, startsWith("multipart/form-data")))
                .andRespond(withSuccess(expectedPdf, MediaType.APPLICATION_PDF));

        byte[] docxPdf = converter.docxToPdf(new ByteArrayInputStream("fake-docx-content".getBytes(StandardCharsets.UTF_8)));
        assertThat(docxPdf).isEqualTo(expectedPdf);

        byte[] htmlPdf = converter.htmlToPdf(new ByteArrayInputStream("<html><body>Hello</body></html>".getBytes(StandardCharsets.UTF_8)));
        assertThat(htmlPdf).isEqualTo(expectedPdf);

        mockServer.verify();
    }

    @Test
    @DisplayName("安全与参数校验：baseUrl 为空或入参流为 null 时立即快速失败")
    void testDefensiveValidation() {
        DocumentProperties invalidProps = new DocumentProperties();
        invalidProps.getConverter().getGotenberg().setEnabled(true);
        invalidProps.getConverter().getGotenberg().setBaseUrl("   ");

        assertThatThrownBy(() -> new GotenbergDocumentConverter(invalidProps))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseUrl");

        DocumentProperties badSchemeProps = createValidProperties("ftp://gotenberg:3000");
        assertThatThrownBy(() -> new GotenbergDocumentConverter(badSchemeProps))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseUrl");

        DocumentProperties zeroTimeoutProps = createValidProperties("http://localhost:3000");
        zeroTimeoutProps.getConverter().getGotenberg().setReadTimeout(Duration.ZERO);
        assertThatThrownBy(() -> new GotenbergDocumentConverter(zeroTimeoutProps))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("readTimeout");

        DocumentProperties validProps = createValidProperties("http://localhost:3000");
        GotenbergDocumentConverter converter = new GotenbergDocumentConverter(validProps);

        assertThatThrownBy(() -> converter.docxToPdf(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> converter.htmlToPdf(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("体积边界：输入流超过 maxDocumentSize 时直接抛 DOCUMENT_SIZE_EXCEEDED 且不发 HTTP 请求")
    void testOversizedStreamRejectedBeforeHttpCall() {
        DocumentProperties properties = createValidProperties("http://gotenberg.internal:3000");
        properties.setMaxDocumentSize(DataSize.ofBytes(16));

        RestClient.Builder builder = RestClient.builder().baseUrl("http://gotenberg.internal:3000");
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        GotenbergDocumentConverter converter = new GotenbergDocumentConverter(properties, builder.build());

        byte[] oversized = new byte[64];
        assertThatThrownBy(() -> converter.docxToPdf(new ByteArrayInputStream(oversized)))
                .isInstanceOf(DocumentException.class)
                .hasFieldOrPropertyWithValue("resultCode", DocumentResultCode.DOCUMENT_SIZE_EXCEEDED);

        mockServer.verify();
    }

    @Test
    @DisplayName("异常处理：Gotenberg 服务端返回 5xx 或空响应体时转译为 CONVERSION_FAILED 并保留 cause 链")
    void testServerErrorAndEmptyBodyTranslated() {
        DocumentProperties properties = createValidProperties("http://gotenberg.internal:3000");
        RestClient.Builder builder = RestClient.builder().baseUrl("http://gotenberg.internal:3000");
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        GotenbergDocumentConverter converter = new GotenbergDocumentConverter(properties, builder.build());

        mockServer.expect(requestTo("http://gotenberg.internal:3000/forms/libreoffice/convert"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        mockServer.expect(requestTo("http://gotenberg.internal:3000/forms/chromium/convert/html"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(new byte[0], MediaType.APPLICATION_PDF));

        assertThatThrownBy(() -> converter.docxToPdf(new ByteArrayInputStream(new byte[]{1, 2, 3})))
                .isInstanceOf(DocumentException.class)
                .hasFieldOrPropertyWithValue("resultCode", DocumentResultCode.CONVERSION_FAILED)
                .hasCauseInstanceOf(Exception.class);

        assertThatThrownBy(() -> converter.htmlToPdf(new ByteArrayInputStream("<h1>x</h1>".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(DocumentException.class)
                .hasFieldOrPropertyWithValue("resultCode", DocumentResultCode.CONVERSION_FAILED);

        mockServer.verify();
    }

    private DocumentProperties createValidProperties(String baseUrl) {
        DocumentProperties properties = new DocumentProperties();
        properties.getConverter().getGotenberg().setEnabled(true);
        properties.getConverter().getGotenberg().setBaseUrl(baseUrl);
        properties.getConverter().getGotenberg().setConnectTimeout(Duration.ofSeconds(2));
        properties.getConverter().getGotenberg().setReadTimeout(Duration.ofSeconds(10));
        return properties;
    }
}
