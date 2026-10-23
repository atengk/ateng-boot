package io.github.atengk.boot.document.spi;

import java.io.InputStream;

/**
 * 文档跨格式转换统一 SPI 扩展点与逃生舱契约
 *
 * @author Ateng
 * @since 2026-10-23
 */
public interface DocumentConverter {

    /**
     * 将 Word (.docx) 文档输入流转换为 PDF 字节数组
     *
     * @param docxInputStream Word 输入流（非空）
     * @return 转换后的 PDF 二进制字节数组
     * @throws io.github.atengk.boot.document.exception.UnsupportedConversionException 当未接入外部转换驱动时快速失败
     */
    byte[] docxToPdf(InputStream docxInputStream);

    /**
     * 将 HTML 内容输入流转换为 PDF 字节数组
     *
     * @param htmlInputStream HTML 输入流（非空）
     * @return 转换后的 PDF 二进制字节数组
     * @throws io.github.atengk.boot.document.exception.UnsupportedConversionException 当未接入外部转换驱动时快速失败
     */
    byte[] htmlToPdf(InputStream htmlInputStream);
}
