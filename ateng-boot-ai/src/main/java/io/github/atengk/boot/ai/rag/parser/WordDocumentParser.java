package io.github.atengk.boot.ai.rag.parser;

import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.rag.parser.multimodal.VisionCaptionEnhancer;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 基于 Apache POI 的 Word (.docx) 结构化与多模态文档解析器
 * <p>
 * 支持提取 Word 复杂文档的正文段落、标题层级树（自动转译为 Markdown 标头结构）
 * 与内嵌表格（自动转译为 Markdown 规范表格），并联动 {@link VisionCaptionEnhancer}
 * 自动识别提取内嵌图片、架构图与流程图并生成结构化语义视觉摘要。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public class WordDocumentParser implements DocumentParser {

    private final VisionCaptionEnhancer visionCaptionEnhancer;

    public WordDocumentParser() {
        this(null);
    }

    public WordDocumentParser(VisionCaptionEnhancer visionCaptionEnhancer) {
        this.visionCaptionEnhancer = visionCaptionEnhancer;
    }

    @Override
    public boolean supports(DocumentFormat format, String filename) {
        if (format == DocumentFormat.WORD) {
            return true;
        }
        if (format == DocumentFormat.UNKNOWN && StringUtils.hasText(filename)) {
            DocumentFormat detected = DocumentFormat.fromFilename(filename);
            return detected == DocumentFormat.WORD;
        }
        return false;
    }

    @Override
    public ParsedDocument parse(InputStream in, ParseOptions options) {
        if (in == null) {
            throw new IllegalArgumentException("文档输入流 InputStream 不能为 null");
        }
        ParseOptions effectiveOptions = options != null ? options : ParseOptions.defaults();

        try (XWPFDocument doc = new XWPFDocument(in)) {
            StringBuilder sb = new StringBuilder();
            String title = null;

            // 1. 尝试从 CoreProperties 读取文档标题
            if (effectiveOptions.isExtractTitle() && doc.getProperties() != null
                    && doc.getProperties().getCoreProperties() != null) {
                String coreTitle = doc.getProperties().getCoreProperties().getTitle();
                if (StringUtils.hasText(coreTitle)) {
                    title = coreTitle.trim();
                }
            }

            // 2. 遍历正文元素 (段落与表格)
            List<IBodyElement> elements = doc.getBodyElements();
            for (IBodyElement element : elements) {
                if (element instanceof XWPFParagraph para) {
                    String text = para.getText();
                    boolean hasText = StringUtils.hasText(text);
                    List<XWPFPicture> paraPictures = extractPictures(para);
                    if (!hasText && paraPictures.isEmpty()) {
                        continue;
                    }

                    if (hasText) {
                        String trimmed = text.trim();
                        String style = para.getStyle();
                        int headingLevel = resolveHeadingLevel(style);
                        if (headingLevel > 0) {
                            String prefix = "#".repeat(headingLevel);
                            sb.append(prefix).append(" ").append(trimmed).append("\n\n");
                            if (!StringUtils.hasText(title) && effectiveOptions.isExtractTitle() && headingLevel == 1) {
                                title = trimmed;
                            }
                        } else {
                            sb.append(trimmed).append("\n\n");
                            if (!StringUtils.hasText(title) && effectiveOptions.isExtractTitle()) {
                                title = trimmed.length() > 80 ? trimmed.substring(0, 80) : trimmed;
                            }
                        }
                    }

                    // 提取并增强段落内嵌图表
                    if (!paraPictures.isEmpty()) {
                        for (XWPFPicture pic : paraPictures) {
                            XWPFPictureData picData = pic.getPictureData();
                            if (picData != null) {
                                byte[] picBytes = picData.getData();
                                String fileName = picData.getFileName();
                                String mimeType = resolveMimeType(fileName);
                                if (visionCaptionEnhancer != null && visionCaptionEnhancer.isEnabled()) {
                                    String caption = visionCaptionEnhancer.enhanceImage(picBytes, fileName, mimeType);
                                    if (StringUtils.hasText(caption)) {
                                        sb.append(caption).append("\n\n");
                                    }
                                } else {
                                    sb.append("> 🖼️ [内嵌图表: ").append(StringUtils.hasText(fileName) ? fileName : "image").append("]\n\n");
                                }
                            }
                        }
                    }
                } else if (element instanceof XWPFTable table) {
                    String tableMarkdown = formatTableToMarkdown(table);
                    if (StringUtils.hasText(tableMarkdown)) {
                        sb.append(tableMarkdown).append("\n\n");
                    }
                }
            }

            String content = sb.toString().trim();

            // 3. 标题平滑回退 (文件名)
            if (!StringUtils.hasText(title) && StringUtils.hasText(effectiveOptions.getFilename())) {
                int dotIdx = effectiveOptions.getFilename().lastIndexOf('.');
                title = dotIdx > 0 ? effectiveOptions.getFilename().substring(0, dotIdx) : effectiveOptions.getFilename();
            }

            // 4. 最大字符数截断
            Integer maxChars = effectiveOptions.getMaxCharacters();
            if (maxChars != null && maxChars > 0 && content.length() > maxChars) {
                content = content.substring(0, maxChars);
            }

            // 5. 页数推断与元数据抽取
            int pageCount = 1;
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (doc.getProperties() != null) {
                if (doc.getProperties().getExtendedProperties() != null) {
                    int pages = doc.getProperties().getExtendedProperties().getPages();
                    if (pages > 0) {
                        pageCount = pages;
                    }
                }
                if (effectiveOptions.isExtractMetadata() && doc.getProperties().getCoreProperties() != null) {
                    var core = doc.getProperties().getCoreProperties();
                    if (StringUtils.hasText(core.getCreator())) {
                        metadata.put("creator", core.getCreator().trim());
                    }
                    if (StringUtils.hasText(core.getSubject())) {
                        metadata.put("subject", core.getSubject().trim());
                    }
                    if (StringUtils.hasText(core.getDescription())) {
                        metadata.put("description", core.getDescription().trim());
                    }
                    if (core.getCreated() != null) {
                        metadata.put("creation_date", core.getCreated().toString());
                    }
                }
            }

            if (effectiveOptions.isExtractMetadata()) {
                if (StringUtils.hasText(effectiveOptions.getFilename())) {
                    metadata.put(ParsedDocument.METADATA_FILENAME, effectiveOptions.getFilename());
                }
                if (effectiveOptions.getCustomOptions() != null && !effectiveOptions.getCustomOptions().isEmpty()) {
                    metadata.putAll(effectiveOptions.getCustomOptions());
                }
            }

            return ParsedDocument.builder()
                    .text(content)
                    .title(title)
                    .characterCount(content.length())
                    .pageCount(pageCount)
                    .format(DocumentFormat.WORD)
                    .metadata(metadata)
                    .build();
        } catch (IOException e) {
            throw new AiException(AiResultCode.AI_PARSE_ERROR, "读取与解析 Word 文档失败: " + e.getMessage(), e);
        }
    }

    /**
     * 判断并提取标题层级 (1~6，0 表示普通段落)
     */
    private int resolveHeadingLevel(String style) {
        if (!StringUtils.hasText(style)) {
            return 0;
        }
        String lower = style.trim().toLowerCase(Locale.ROOT);
        if (lower.contains("heading") || lower.contains("标题")) {
            for (int i = 1; i <= 6; i++) {
                if (lower.contains(String.valueOf(i))) {
                    return i;
                }
            }
            return 1;
        }
        if (lower.matches("^[1-6]$")) {
            return Integer.parseInt(lower);
        }
        return 0;
    }

    /**
     * 将 Word 表格格式化为规范 Markdown 表格
     */
    private String formatTableToMarkdown(XWPFTable table) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        StringBuilder tableSb = new StringBuilder();
        int maxCols = 0;
        for (XWPFTableRow row : rows) {
            maxCols = Math.max(maxCols, row.getTableCells().size());
        }
        if (maxCols == 0) {
            return "";
        }

        // 第一行作为表头
        XWPFTableRow headerRow = rows.get(0);
        appendRow(tableSb, headerRow, maxCols);

        // 分隔行
        tableSb.append("|");
        for (int i = 0; i < maxCols; i++) {
            tableSb.append(" --- |");
        }
        tableSb.append("\n");

        // 数据行
        for (int i = 1; i < rows.size(); i++) {
            appendRow(tableSb, rows.get(i), maxCols);
        }

        return tableSb.toString().trim();
    }

    private void appendRow(StringBuilder sb, XWPFTableRow row, int maxCols) {
        sb.append("|");
        List<XWPFTableCell> cells = row.getTableCells();
        for (int i = 0; i < maxCols; i++) {
            String cellText = i < cells.size() ? cells.get(i).getText() : "";
            String clean = cellText != null ? cellText.replace("\n", " ").replace("|", "\\|").trim() : "";
            sb.append(" ").append(clean).append(" |");
        }
        sb.append("\n");
    }

    /**
     * 抽取段落中内嵌的所有图片对象
     */
    private List<XWPFPicture> extractPictures(XWPFParagraph para) {
        List<XWPFPicture> pictures = new ArrayList<>();
        if (para.getRuns() != null) {
            for (XWPFRun run : para.getRuns()) {
                List<XWPFPicture> runPics = run.getEmbeddedPictures();
                if (runPics != null && !runPics.isEmpty()) {
                    pictures.addAll(runPics);
                }
            }
        }
        return pictures;
    }

    /**
     * 根据图片文件名后缀解析 MIME 类型
     */
    private String resolveMimeType(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "image/png";
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        } else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (lower.endsWith(".gif")) {
            return "image/gif";
        } else if (lower.endsWith(".webp")) {
            return "image/webp";
        } else if (lower.endsWith(".bmp")) {
            return "image/bmp";
        } else if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "image/png";
    }

    @Override
    public int getOrder() {
        return 20;
    }
}
