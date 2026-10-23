package io.github.atengk.boot.document.spi;

import io.github.atengk.boot.document.exception.DocumentResultCode;
import io.github.atengk.boot.document.exception.UnsupportedConversionException;

import java.io.InputStream;

/**
 * 缺省文档转换器实现（提供优雅快速失败与清晰扩展指引）
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class DefaultDocumentConverter implements DocumentConverter {

    @Override
    public byte[] docxToPdf(InputStream docxInputStream) {
        throw new UnsupportedConversionException(
                DocumentResultCode.UNSUPPORTED_CONVERSION,
                "当前未配置外部 Word 转 PDF 渲染器驱动。底座恪守轻量纯净原则未内置重型渲染进程，" +
                "如需高保真 Word 转 PDF，请实现并注册自定义 DocumentConverter Bean（例如接入 Gotenberg 或外部 LibreOffice 服务）。"
        );
    }

    @Override
    public byte[] htmlToPdf(InputStream htmlInputStream) {
        throw new UnsupportedConversionException(
                DocumentResultCode.UNSUPPORTED_CONVERSION,
                "当前未配置外部 HTML 转 PDF 渲染器驱动。底座恪守轻量纯净原则未内置重型渲染进程，" +
                "如需 HTML 转 PDF，请实现并注册自定义 DocumentConverter Bean（例如接入 OpenHTMLtoPDF 或 Gotenberg 服务）。"
        );
    }
}
