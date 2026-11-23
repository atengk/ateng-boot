package io.github.atengk.boot.ai.rag.parser;

import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.rag.parser.multimodal.VisionCaptionEnhancer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 Apache PDFBox 3.x 的 PDF 结构化与多模态文档解析器
 * <p>
 * 依托 Apache PDFBox 3.x 提取 PDF 文档的多页纯净文本、总页数与文档信息元数据（标题、作者、主题、关键字等），
 * 并联动 {@link VisionCaptionEnhancer} 支持内嵌图表自动视觉摘要与扫描件页面 150 DPI 栅格化视觉 OCR。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public class PdfDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(PdfDocumentParser.class);

    private final VisionCaptionEnhancer visionCaptionEnhancer;

    public PdfDocumentParser() {
        this(null);
    }

    public PdfDocumentParser(VisionCaptionEnhancer visionCaptionEnhancer) {
        this.visionCaptionEnhancer = visionCaptionEnhancer;
    }

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

            String cleanText;
            List<String> collectedImageUrls = new ArrayList<>();
            List<String> collectedStorageKeys = new ArrayList<>();
            List<String> collectedCaptions = new ArrayList<>();
            if (visionCaptionEnhancer != null && visionCaptionEnhancer.isEnabled()) {
                StringBuilder pageTextSb = new StringBuilder();
                PDFRenderer renderer = null;
                int minWidth = visionCaptionEnhancer.getProperties().getMinImageWidth();
                int minHeight = visionCaptionEnhancer.getProperties().getMinImageHeight();
                int ocrThreshold = visionCaptionEnhancer.getProperties().getScannedPdfThreshold();
                int dpi = visionCaptionEnhancer.getProperties().getScannedPdfDpi();

                for (int pageIdx = 0; pageIdx < pageCount; pageIdx++) {
                    stripper.setStartPage(pageIdx + 1);
                    stripper.setEndPage(pageIdx + 1);
                    String pageText = stripper.getText(document);
                    String trimmedPage = pageText != null ? pageText.trim() : "";

                    PDPage page = document.getPage(pageIdx);
                    PDResources resources = page.getResources();
                    boolean hasImages = false;
                    List<PDImageXObject> pageImages = new ArrayList<>();
                    if (resources != null) {
                        for (COSName name : resources.getXObjectNames()) {
                            if (resources.isImageXObject(name)) {
                                try {
                                    if (resources.getXObject(name) instanceof PDImageXObject imgObj) {
                                        pageImages.add(imgObj);
                                        // 仅当图像尺寸达到有效栅格阈值时才计入大图，避免 1x1 追踪像素误触发扫描件 OCR
                                        if (imgObj.getWidth() >= minWidth && imgObj.getHeight() >= minHeight) {
                                            hasImages = true;
                                        }
                                    }
                                } catch (Exception e) {
                                    log.debug("读取 PDF 页面图片资源异常 [page={}, objectName={}]: {}", pageIdx + 1, name.getName(), e.getMessage());
                                }
                            }
                        }
                    }

                    // 扫描件探测：字符数过少且包含大图片
                    if (trimmedPage.length() < ocrThreshold && hasImages) {
                        try {
                            if (renderer == null) {
                                renderer = new PDFRenderer(document);
                            }
                            BufferedImage bim = renderer.renderImageWithDPI(pageIdx, dpi, ImageType.RGB);
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            ImageIO.write(bim, "png", baos);
                            String ocrText = visionCaptionEnhancer.enhanceScannedPage(baos.toByteArray(), pageIdx + 1);
                            pageTextSb.append(ocrText).append("\n\n");
                        } catch (Exception e) {
                            log.warn("渲染并 OCR 扫描件页面异常 [page={}]: {}", pageIdx + 1, e.getMessage());
                            if (!trimmedPage.isEmpty()) {
                                pageTextSb.append(trimmedPage).append("\n\n");
                            }
                        }
                    } else {
                        // 正常文本页
                        if (!trimmedPage.isEmpty()) {
                            pageTextSb.append(trimmedPage).append("\n\n");
                        }
                        // 提取内嵌架构图/流程图
                        int imgCounter = 0;
                        for (PDImageXObject img : pageImages) {
                            if (img.getWidth() >= minWidth && img.getHeight() >= minHeight) {
                                try {
                                    BufferedImage bImg = img.getImage();
                                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                    ImageIO.write(bImg, "png", baos);
                                    VisionCaptionEnhancer.ImageEnhanceResult enhanceResult = visionCaptionEnhancer.enhanceImageResult(
                                            baos.toByteArray(),
                                            "chart_p" + (pageIdx + 1) + "_" + (++imgCounter) + ".png",
                                            "image/png"
                                    );
                                    if (enhanceResult.imageUrl() != null) {
                                        collectedImageUrls.add(enhanceResult.imageUrl());
                                    }
                                    if (enhanceResult.storageKey() != null) {
                                        collectedStorageKeys.add(enhanceResult.storageKey());
                                    }
                                    if (enhanceResult.caption() != null) {
                                        collectedCaptions.add(enhanceResult.caption());
                                    }
                                    pageTextSb.append(enhanceResult.text()).append("\n\n");
                                } catch (Exception e) {
                                    log.warn("提取并增强 PDF 内嵌图表异常 [page={}, index={}]: {}", pageIdx + 1, imgCounter, e.getMessage());
                                }
                            }
                        }
                    }
                }
                cleanText = pageTextSb.toString().trim();
            } else {
                String rawText = stripper.getText(document);
                cleanText = rawText != null ? rawText.trim() : "";
            }

            // 1. 提取文档标题
            String title = null;
            if (effectiveOptions.isExtractTitle()) {
                PDDocumentInformation info = document.getDocumentInformation();
                if (info != null && StringUtils.hasText(info.getTitle())) {
                    title = info.getTitle().trim();
                }
                if (!StringUtils.hasText(title)) {
                    title = effectiveOptions.extractTitleFromFilename();
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
                if (!collectedImageUrls.isEmpty()) {
                    metadata.put(ParsedDocument.METADATA_IMAGE_URL, collectedImageUrls.get(0));
                    metadata.put(ParsedDocument.METADATA_IMAGE_URLS, collectedImageUrls);
                }
                if (!collectedStorageKeys.isEmpty()) {
                    metadata.put(ParsedDocument.METADATA_IMAGE_STORAGE_KEY, collectedStorageKeys.get(0));
                    metadata.put(ParsedDocument.METADATA_IMAGE_STORAGE_KEYS, collectedStorageKeys);
                }
                if (!collectedCaptions.isEmpty()) {
                    metadata.put(ParsedDocument.METADATA_IMAGE_CAPTIONS, collectedCaptions);
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
