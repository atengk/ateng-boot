package io.github.atengk.boot.document.spi;

import io.github.atengk.boot.document.exception.DocumentResultCode;
import io.github.atengk.boot.document.exception.UnsupportedConversionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文档转换器 SPI 单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("文档转换器 SPI 单元测试")
class DocumentConverterTest {

    private final DocumentConverter converter = new DefaultDocumentConverter();

    @Test
    @DisplayName("测试缺省转换器调用 docxToPdf 抛出受控异常与清晰指引")
    void testDefaultDocxToPdfThrows() {
        ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);
        assertThatThrownBy(() -> converter.docxToPdf(in))
                .isInstanceOf(UnsupportedConversionException.class)
                .satisfies(ex -> {
                    UnsupportedConversionException uce = (UnsupportedConversionException) ex;
                    assertThat(uce.getCode()).isEqualTo(DocumentResultCode.UNSUPPORTED_CONVERSION.getCode());
                    assertThat(uce.getMsg()).contains("当前未配置外部 Word 转 PDF 渲染器驱动");
                    assertThat(uce.getMsg()).contains("Gotenberg");
                });
    }

    @Test
    @DisplayName("测试缺省转换器调用 htmlToPdf 抛出受控异常与清晰指引")
    void testDefaultHtmlToPdfThrows() {
        ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);
        assertThatThrownBy(() -> converter.htmlToPdf(in))
                .isInstanceOf(UnsupportedConversionException.class)
                .satisfies(ex -> {
                    UnsupportedConversionException uce = (UnsupportedConversionException) ex;
                    assertThat(uce.getCode()).isEqualTo(DocumentResultCode.UNSUPPORTED_CONVERSION.getCode());
                    assertThat(uce.getMsg()).contains("当前未配置外部 HTML 转 PDF 渲染器驱动");
                    assertThat(uce.getMsg()).contains("OpenHTMLtoPDF");
                });
    }
}
