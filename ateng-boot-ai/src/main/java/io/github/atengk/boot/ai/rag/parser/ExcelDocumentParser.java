package io.github.atengk.boot.ai.rag.parser;

import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import org.apache.fesod.sheet.FesodSheet;
import org.apache.fesod.sheet.context.AnalysisContext;
import org.apache.fesod.sheet.event.AnalysisEventListener;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 Apache Fesod 流式电子表格引擎的 Excel (.xlsx / .xls / .csv) 结构化文档解析器
 * <p>
 * 高性能流式解析多工作表（Sheet）表格，自动提取表头与数据行，将其规范化渲染为 Markdown 格式表格流，
 * 彻底解决电子表格在向量化检索时表头与数据断裂解耦的痛点。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public class ExcelDocumentParser implements DocumentParser {

    @Override
    public boolean supports(DocumentFormat format, String filename) {
        if (format == DocumentFormat.EXCEL || format == DocumentFormat.CSV) {
            return true;
        }
        if (format == DocumentFormat.UNKNOWN && StringUtils.hasText(filename)) {
            DocumentFormat detected = DocumentFormat.fromFilename(filename);
            return detected == DocumentFormat.EXCEL || detected == DocumentFormat.CSV;
        }
        return false;
    }

    @Override
    public ParsedDocument parse(InputStream in, ParseOptions options) {
        if (in == null) {
            throw new IllegalArgumentException("文档输入流 InputStream 不能为 null");
        }
        ParseOptions effectiveOptions = options != null ? options : ParseOptions.defaults();

        Map<String, Map<Integer, String>> sheetHeaders = new LinkedHashMap<>();
        Map<String, List<Map<Integer, String>>> sheetRows = new LinkedHashMap<>();

        try {
            AnalysisEventListener<Map<Integer, String>> listener = new AnalysisEventListener<>() {
                private String resolveSheetName(AnalysisContext context) {
                    if (context != null && context.readSheetHolder() != null
                            && StringUtils.hasText(context.readSheetHolder().getSheetName())) {
                        return context.readSheetHolder().getSheetName().trim();
                    }
                    int sheetNo = (context != null && context.readSheetHolder() != null)
                            ? context.readSheetHolder().getSheetNo()
                            : 0;
                    return "Sheet" + (sheetNo + 1);
                }

                @Override
                public void invokeHeadMap(Map<Integer, String> headMap, AnalysisContext context) {
                    String sheetName = resolveSheetName(context);
                    if (headMap != null && !headMap.isEmpty()) {
                        sheetHeaders.put(sheetName, new LinkedHashMap<>(headMap));
                    }
                }

                @Override
                public void invoke(Map<Integer, String> data, AnalysisContext context) {
                    String sheetName = resolveSheetName(context);
                    if (data != null && !data.isEmpty()) {
                        sheetRows.computeIfAbsent(sheetName, k -> new ArrayList<>()).add(new LinkedHashMap<>(data));
                    }
                }

                @Override
                public void doAfterAllAnalysed(AnalysisContext context) {
                }
            };

            FesodSheet.read(in, listener).doReadAll();
        } catch (Exception e) {
            throw new AiException(AiResultCode.AI_PARSE_ERROR, "读取与流式解析 Excel 电子表格失败: " + e.getMessage(), e);
        }

        // 格式化为 Markdown 表格流
        StringBuilder markdownSb = new StringBuilder();
        List<String> allSheetNames = new ArrayList<>();

        for (Map.Entry<String, List<Map<Integer, String>>> entry : sheetRows.entrySet()) {
            String sheetName = entry.getKey();
            allSheetNames.add(sheetName);
            List<Map<Integer, String>> rows = entry.getValue();
            Map<Integer, String> headers = sheetHeaders.get(sheetName);

            markdownSb.append("## 工作表: ").append(sheetName).append("\n\n");

            // 计算所有列的最大列索引
            int maxCol = -1;
            if (headers != null) {
                for (Integer colIdx : headers.keySet()) {
                    if (colIdx != null && colIdx > maxCol) {
                        maxCol = colIdx;
                    }
                }
            }
            for (Map<Integer, String> row : rows) {
                for (Integer colIdx : row.keySet()) {
                    if (colIdx != null && colIdx > maxCol) {
                        maxCol = colIdx;
                    }
                }
            }

            if (maxCol >= 0) {
                // 表头行
                markdownSb.append("|");
                for (int col = 0; col <= maxCol; col++) {
                    String headerText = (headers != null && headers.get(col) != null)
                            ? headers.get(col)
                            : "列" + (col + 1);
                    markdownSb.append(" ").append(cleanCell(headerText)).append(" |");
                }
                markdownSb.append("\n");

                // 分隔行
                markdownSb.append("|");
                for (int col = 0; col <= maxCol; col++) {
                    markdownSb.append(" --- |");
                }
                markdownSb.append("\n");

                // 数据行
                for (Map<Integer, String> row : rows) {
                    markdownSb.append("|");
                    for (int col = 0; col <= maxCol; col++) {
                        String cellVal = row.get(col);
                        markdownSb.append(" ").append(cleanCell(cellVal)).append(" |");
                    }
                    markdownSb.append("\n");
                }
                markdownSb.append("\n");
            }
        }

        String content = markdownSb.toString().trim();

        // 提取标题
        String title = null;
        if (effectiveOptions.isExtractTitle()) {
            if (StringUtils.hasText(effectiveOptions.getFilename())) {
                int dotIdx = effectiveOptions.getFilename().lastIndexOf('.');
                title = dotIdx > 0 ? effectiveOptions.getFilename().substring(0, dotIdx) : effectiveOptions.getFilename();
            } else if (!allSheetNames.isEmpty()) {
                title = allSheetNames.get(0);
            }
        }

        // 截断最大字符数
        Integer maxChars = effectiveOptions.getMaxCharacters();
        if (maxChars != null && maxChars > 0 && content.length() > maxChars) {
            content = content.substring(0, maxChars);
        }

        // 元数据
        Map<String, Object> metadata = new LinkedHashMap<>();
        int sheetCount = allSheetNames.isEmpty() ? 1 : allSheetNames.size();
        if (effectiveOptions.isExtractMetadata()) {
            metadata.put("sheet_count", sheetCount);
            metadata.put("sheet_names", String.join(", ", allSheetNames));
            if (StringUtils.hasText(effectiveOptions.getFilename())) {
                metadata.put(ParsedDocument.METADATA_FILENAME, effectiveOptions.getFilename());
            }
            if (effectiveOptions.getCustomOptions() != null && !effectiveOptions.getCustomOptions().isEmpty()) {
                metadata.putAll(effectiveOptions.getCustomOptions());
            }
        }

        DocumentFormat format = (effectiveOptions.getFormat() == DocumentFormat.CSV
                || (StringUtils.hasText(effectiveOptions.getFilename()) && effectiveOptions.getFilename().toLowerCase().endsWith(".csv")))
                ? DocumentFormat.CSV
                : DocumentFormat.EXCEL;

        return ParsedDocument.builder()
                .text(content)
                .title(title)
                .characterCount(content.length())
                .pageCount(sheetCount)
                .format(format)
                .metadata(metadata)
                .build();
    }

    private String cleanCell(String value) {
        if (!StringUtils.hasText(value)) {
            return "-";
        }
        return value.replace("\r", " ").replace("\n", " ").replace("|", "\\|").trim();
    }

    @Override
    public int getOrder() {
        return 30;
    }
}
