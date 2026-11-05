package io.github.atengk.boot.ai.rag.splitter;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 原生表格感知智能分块切片器 (TableSplitter)
 * <p>
 * 专门针对 Excel、CSV 与 Markdown 表格长数据设计，支持智能探测表格边界，
 * 在分块时为每一个切片片段强制附加原始列字段表头与架构描述，彻底杜绝数据行脱离表头导致的语义断裂与检索失效。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public class TableSplitter implements DocumentSplitter {

    public static final int DEFAULT_ROWS_PER_CHUNK = 20;
    public static final int DEFAULT_ROW_OVERLAP = 1;
    public static final int DEFAULT_MAX_CHUNK_SIZE = 1200;

    private static final Pattern TABLE_SEPARATOR_PATTERN = Pattern.compile("^\\|(\\s*:?-+:?\\s*\\|)+\\s*$");

    private final int maxRowsPerChunk;
    private final int rowOverlap;
    private final int maxChunkSize;
    private final boolean includeTableDescription;

    public TableSplitter() {
        this(DEFAULT_ROWS_PER_CHUNK, DEFAULT_ROW_OVERLAP, DEFAULT_MAX_CHUNK_SIZE, true);
    }

    public TableSplitter(int maxRowsPerChunk) {
        this(maxRowsPerChunk, DEFAULT_ROW_OVERLAP, DEFAULT_MAX_CHUNK_SIZE, true);
    }

    public TableSplitter(int maxRowsPerChunk, int rowOverlap) {
        this(maxRowsPerChunk, rowOverlap, DEFAULT_MAX_CHUNK_SIZE, true);
    }

    public TableSplitter(int maxRowsPerChunk, int rowOverlap, int maxChunkSize) {
        this(maxRowsPerChunk, rowOverlap, maxChunkSize, true);
    }

    public TableSplitter(int maxRowsPerChunk, int rowOverlap, int maxChunkSize, boolean includeTableDescription) {
        Assert.isTrue(maxRowsPerChunk > 0, "maxRowsPerChunk 必须大于 0");
        Assert.isTrue(rowOverlap >= 0, "rowOverlap 不能小于 0");
        Assert.isTrue(rowOverlap < maxRowsPerChunk, "rowOverlap 必须小于 maxRowsPerChunk");
        Assert.isTrue(maxChunkSize > 0, "maxChunkSize 必须大于 0");
        this.maxRowsPerChunk = maxRowsPerChunk;
        this.rowOverlap = rowOverlap;
        this.maxChunkSize = maxChunkSize;
        this.includeTableDescription = includeTableDescription;
    }

    public int getMaxRowsPerChunk() {
        return maxRowsPerChunk;
    }

    public int getRowOverlap() {
        return rowOverlap;
    }

    public int getMaxChunkSize() {
        return maxChunkSize;
    }

    public boolean isIncludeTableDescription() {
        return includeTableDescription;
    }

    @Override
    public List<TextSegment> split(String text, Metadata metadata) {
        if (!StringUtils.hasText(text)) {
            return Collections.emptyList();
        }
        Metadata baseMetadata = metadata != null ? metadata.copy() : new Metadata();
        List<TextSegment> results = new ArrayList<>();

        String[] rawLines = text.split("\\r?\\n");
        List<String> currentNonTableLines = new ArrayList<>();
        List<String> currentTableLines = new ArrayList<>();
        String lastHeading = null;

        for (int i = 0; i < rawLines.length; i++) {
            String line = rawLines[i];
            String trimmed = line.trim();

            if (isTableLine(trimmed)) {
                currentTableLines.add(line);
            } else {
                if (!currentTableLines.isEmpty()) {
                    // 遇到表格结束，处理前一个表格
                    processTableBlock(currentTableLines, lastHeading, baseMetadata, results);
                    currentTableLines.clear();
                }
                if (trimmed.startsWith("#")) {
                    lastHeading = trimmed;
                }
                if (!trimmed.isEmpty()) {
                    currentNonTableLines.add(line);
                }
            }
        }

        // 处理尾部遗留的表格或文本
        if (!currentTableLines.isEmpty()) {
            processTableBlock(currentTableLines, lastHeading, baseMetadata, results);
            currentTableLines.clear();
        }

        // 如果整个文本完全不含表格，则按普通非表格段落处理
        if (results.isEmpty() && !currentNonTableLines.isEmpty()) {
            String nonTableContent = String.join("\n", currentNonTableLines).trim();
            if (!nonTableContent.isEmpty()) {
                results.add(TextSegment.from(nonTableContent, baseMetadata.copy()));
            }
        }

        return Collections.unmodifiableList(results);
    }

    /**
     * 编程式直接切片结构化表头与数据行
     *
     * @param headers    列名表头列表
     * @param rows       数据行列表
     * @param tableTitle 表格标题或描述说明 (可选)
     * @param metadata   初始元数据 (可选)
     * @return 带表头守卫的 TextSegment 片段列表
     */
    public List<TextSegment> splitTable(List<String> headers,
                                        List<List<String>> rows,
                                        String tableTitle,
                                        Metadata metadata) {
        if (headers == null || headers.isEmpty()) {
            return Collections.emptyList();
        }
        Metadata baseMeta = metadata != null ? metadata.copy() : new Metadata();
        List<TextSegment> segments = new ArrayList<>();

        String headerLine = "| " + String.join(" | ", headers) + " |";
        StringBuilder sepSb = new StringBuilder("|");
        for (int i = 0; i < headers.size(); i++) {
            sepSb.append(" --- |");
        }
        String separatorLine = sepSb.toString();

        List<String> dataLines = new ArrayList<>();
        if (rows != null) {
            for (List<String> row : rows) {
                dataLines.add("| " + String.join(" | ", row) + " |");
            }
        }

        sliceAndEmitTable(headerLine, separatorLine, dataLines, tableTitle, baseMeta, segments);
        return Collections.unmodifiableList(segments);
    }

    private boolean isTableLine(String line) {
        return line.startsWith("|") && line.endsWith("|");
    }

    private void processTableBlock(List<String> tableLines,
                                   String tableHeading,
                                   Metadata baseMetadata,
                                   List<TextSegment> results) {
        if (tableLines.size() < 2) {
            // 不足两行无法构成合法 Markdown 表格 (至少需要表头和分隔符)
            String raw = String.join("\n", tableLines).trim();
            if (!raw.isEmpty()) {
                results.add(TextSegment.from(raw, baseMetadata.copy()));
            }
            return;
        }

        String headerLine = tableLines.get(0);
        String secondLine = tableLines.get(1);

        int dataStartIndex = 2;
        String separatorLine = secondLine;
        if (!TABLE_SEPARATOR_PATTERN.matcher(secondLine.trim()).matches()) {
            // 第二行不是分隔线，合成默认分隔线
            int colCount = headerLine.split("\\|").length - 1;
            colCount = Math.max(colCount, 1);
            StringBuilder sb = new StringBuilder("|");
            for (int i = 0; i < colCount; i++) {
                sb.append(" --- |");
            }
            separatorLine = sb.toString();
            dataStartIndex = 1;
        }

        List<String> dataLines = new ArrayList<>();
        for (int i = dataStartIndex; i < tableLines.size(); i++) {
            dataLines.add(tableLines.get(i));
        }

        sliceAndEmitTable(headerLine, separatorLine, dataLines, tableHeading, baseMetadata, results);
    }

    private void sliceAndEmitTable(String headerLine,
                                   String separatorLine,
                                   List<String> dataLines,
                                   String tableHeading,
                                   Metadata baseMetadata,
                                   List<TextSegment> results) {
        if (dataLines.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            if (includeTableDescription && StringUtils.hasText(tableHeading)) {
                sb.append(tableHeading).append("\n\n");
            }
            sb.append(headerLine).append("\n").append(separatorLine);
            Metadata meta = baseMetadata.copy();
            meta.put("is_table_chunk", "true");
            meta.put("table_chunk_index", "0");
            meta.put("table_total_chunks", "1");
            meta.put("table_rows", "0");
            results.add(TextSegment.from(sb.toString(), meta));
            return;
        }

        List<List<String>> chunks = new ArrayList<>();
        int step = maxRowsPerChunk - rowOverlap;
        for (int i = 0; i < dataLines.size(); i += step) {
            int end = Math.min(i + maxRowsPerChunk, dataLines.size());
            List<String> chunkRows = new ArrayList<>(dataLines.subList(i, end));
            chunks.add(chunkRows);
            if (end >= dataLines.size()) {
                break;
            }
        }

        int totalChunks = chunks.size();
        for (int chunkIdx = 0; chunkIdx < totalChunks; chunkIdx++) {
            List<String> rows = chunks.get(chunkIdx);
            StringBuilder sb = new StringBuilder();

            if (includeTableDescription && StringUtils.hasText(tableHeading)) {
                sb.append(tableHeading);
                if (totalChunks > 1) {
                    sb.append(" (片段 ").append(chunkIdx + 1).append("/").append(totalChunks).append(")");
                }
                sb.append("\n\n");
            }

            sb.append(headerLine).append("\n");
            sb.append(separatorLine).append("\n");
            for (String row : rows) {
                sb.append(row).append("\n");
            }

            String chunkText = sb.toString().trim();
            Metadata meta = baseMetadata.copy();
            meta.put("is_table_chunk", "true");
            meta.put("table_chunk_index", String.valueOf(chunkIdx));
            meta.put("table_total_chunks", String.valueOf(totalChunks));
            meta.put("table_rows", String.valueOf(rows.size()));
            if (StringUtils.hasText(tableHeading)) {
                meta.put("table_heading", tableHeading);
            }
            results.add(TextSegment.from(chunkText, meta));
        }
    }
}
