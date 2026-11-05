package io.github.atengk.boot.ai.rag.parser;

import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基于 Apache PDFBox 3.x 的 PDF 结构化文档解析器
 * <p>
 * 依托 Apache PDFBox 3.x 提取 PDF 文档的多页纯净文本、总页数与文档信息元数据（标题、作者、主题、关键字等），
 * 在未配置或无法提取内部元数据标题时，支持自适应回退文件名与正文首行。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public class PdfDocumentParser implements DocumentParser {

    @Override
    public boolean supports(DocumentFormat format, String filename) {
        if (format == DocumentFormat.PDF) {
            return true;
        }
        if (format == DocumentFormat.UNKNOWN && StringUtils.hasText(filename)) {
            DocumentFormat detected = DocumentFormat.fromFilename(filename);
            return detected == DocumentFormat.PDF;
        }
        return false;
    }

    @Override
    public ParsedDocument parse(InputStream in, ParseOptions options) {
        if (in == null) {
            throw new IllegalArgumentException("文档输入流 InputStream 不能为 null");
        }
        ParseOptions effectiveOptions = options != null ? options : ParseOptions.defaults();

        try (PDDocument document = Loader.loadPDF(new RandomAccessReadBuffer(in))) {
            int pageCount = document.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String rawText = stripper.getText(document);
            String cleanText = rawText != null ? rawText.trim() : "";

            // 1. 提取文档标题
            String title = null;
            if (effectiveOptions.isExtractTitle()) {
                PDDocumentInformation info = document.getDocumentInformation();
                if (info != null && StringUtils.hasText(info.getTitle())) {
                    title = info.getTitle().trim();
                }
                if (!StringUtils.hasText(title) && StringUtils.hasText(effectiveOptions.getFilename())) {
                    int dotIdx = effectiveOptions.getFilename().lastIndexOf('.');
                    title = dotIdx > 0 ? effectiveOptions.getFilename().substring(0, dotIdx) : effectiveOptions.getFilename();
                }
                if (!StringUtils.hasText(title) && StringUtils.hasText(cleanText)) {
                    for (String line : cleanText.split("\\r?\\n")) {
                        String trimmed = line.trim();
                        if (!trimmed.isEmpty()) {
                            title = trimmed.length() > 80 ? trimmed.substring(0, 80) : trimmed;
                            break;
                        }
                    }
                }
            }

            // 2. 截断最大字符数
            Integer maxChars = effectiveOptions.getMaxCharacters();
            if (maxChars != null && maxChars > 0 && cleanText.length() > maxChars) {
                cleanText = cleanText.substring(0, maxChars);
            }

            // 3. 抽取元数据
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (effectiveOptions.isExtractMetadata()) {
                PDDocumentInformation info = document.getDocumentInformation();
                if (info != null) {
                    if (StringUtils.hasText(info.getAuthor())) {
                        metadata.put("author", info.getAuthor().trim());
                    }
                    if (StringUtils.hasText(info.getSubject())) {
                        metadata.put("subject", info.getSubject().trim());
                    }
                    if (StringUtils.hasText(info.getKeywords())) {
                        metadata.put("keywords", info.getKeywords().trim());
                    }
                    if (StringUtils.hasText(info.getCreator())) {
                        metadata.put("creator", info.getCreator().trim());
                    }
                    if (info.getCreationDate() != null) {
                        metadata.put("creation_date", info.getCreationDate().getTime().toString());
                    }
                }
                if (StringUtils.hasText(effectiveOptions.getFilename())) {
                    metadata.put(ParsedDocument.METADATA_FILENAME, effectiveOptions.getFilename());
                }
                if (effectiveOptions.getCustomOptions() != null && !effectiveOptions.getCustomOptions().isEmpty()) {
                    metadata.putAll(effectiveOptions.getCustomOptions());
                }
            }

            return ParsedDocument.builder()
                    .text(cleanText)
                    .title(title)
                    .characterCount(cleanText.length())
                    .pageCount(pageCount)
                    .format(DocumentFormat.PDF)
                    .metadata(metadata)
                    .build();
        } catch (IOException e) {
            throw new AiException(AiResultCode.AI_PARSE_ERROR, "读取与解析 PDF 文档失败: " + e.getMessage(), e);
        }
    }

    @Override
    public int getOrder() {
        return 10;
    }
}
