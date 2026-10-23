package io.github.atengk.boot.document.exception;

import io.github.atengk.boot.core.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文档治理异常体系与错误码单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("文档治理异常体系与错误码单元测试")
class DocumentExceptionAndResultCodeTest {

    @Test
    @DisplayName("测试状态码枚举编码与消息完整性")
    void testResultCodes() {
        assertThat(DocumentResultCode.DOCUMENT_OPERATION_FAILED.getCode()).isEqualTo(57000);
        assertThat(DocumentResultCode.DOCUMENT_OPERATION_FAILED.getMsg()).isEqualTo("文档处理操作失败");

        assertThat(DocumentResultCode.WORD_RENDER_FAILED.getCode()).isEqualTo(57001);
        assertThat(DocumentResultCode.WORD_RENDER_FAILED.getMsg()).isEqualTo("Word文档模板渲染失败");

        assertThat(DocumentResultCode.PDF_OPERATION_FAILED.getCode()).isEqualTo(57002);
        assertThat(DocumentResultCode.PDF_OPERATION_FAILED.getMsg()).isEqualTo("PDF处理操作失败");

        assertThat(DocumentResultCode.FONT_NOT_FOUND.getCode()).isEqualTo(57003);
        assertThat(DocumentResultCode.FONT_NOT_FOUND.getMsg()).isEqualTo("未找到可用的中文字体");

        assertThat(DocumentResultCode.UNSUPPORTED_CONVERSION.getCode()).isEqualTo(57004);
        assertThat(DocumentResultCode.UNSUPPORTED_CONVERSION.getMsg()).isEqualTo("不支持的文档格式转换");

        assertThat(DocumentResultCode.TEMPLATE_NOT_FOUND.getCode()).isEqualTo(57005);
        assertThat(DocumentResultCode.PASSWORD_ERROR.getCode()).isEqualTo(57006);
        assertThat(DocumentResultCode.FORM_ERROR.getCode()).isEqualTo(57007);
    }

    @Test
    @DisplayName("测试 DocumentException 基础构造与因果链保留")
    void testDocumentException() {
        Exception cause = new RuntimeException("底层 IO 异常");
        DocumentException ex = new DocumentException("操作失败", cause);

        assertThat(ex).isInstanceOf(BusinessException.class);
        assertThat(ex.getCode()).isEqualTo(57000);
        assertThat(ex.getMsg()).isEqualTo("操作失败");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("测试子类异常因果继承与错误码映射")
    void testSubclassExceptions() {
        Exception cause = new IllegalStateException("XML 解析崩溃");

        // WordRenderException
        WordRenderException wordEx = new WordRenderException("Word 渲染崩溃", cause);
        assertThat(wordEx.getCode()).isEqualTo(57001);
        assertThat(wordEx.getMsg()).isEqualTo("Word 渲染崩溃");
        assertThat(wordEx.getCause()).isSameAs(cause);

        // PdfOperationException
        PdfOperationException pdfEx = new PdfOperationException("PDF 水印失败", cause);
        assertThat(pdfEx.getCode()).isEqualTo(57002);
        assertThat(pdfEx.getMsg()).isEqualTo("PDF 水印失败");
        assertThat(pdfEx.getCause()).isSameAs(cause);

        // DocumentFontException
        DocumentFontException fontEx = new DocumentFontException("字体未找到", cause);
        assertThat(fontEx.getCode()).isEqualTo(57003);
        assertThat(fontEx.getMsg()).isEqualTo("字体未找到");
        assertThat(fontEx.getCause()).isSameAs(cause);

        // UnsupportedConversionException
        UnsupportedConversionException convEx = new UnsupportedConversionException("不支持 Word 转 PDF", cause);
        assertThat(convEx.getCode()).isEqualTo(57004);
        assertThat(convEx.getMsg()).isEqualTo("不支持 Word 转 PDF");
        assertThat(convEx.getCause()).isSameAs(cause);
    }
}
