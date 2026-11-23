package io.github.atengk.boot.ai.rag.splitter;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 原生 Markdown 标题层级智能切片器
 * <p>
 * 沿 Markdown 标题层级（{@code #}, {@code ##}, {@code ###} 等）解析并切分文档，
 * 自动识别并继承父级标题元数据；内部支持代码块保护防误切，并在单章节超长时支持平滑级联二次分块。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public class MarkdownHeaderSplitter implements DocumentSplitter {

    private static final Pattern HEADER_PATTERN = Pattern.compile("^(#{1,6})\\s+(.*)$");

    public static final Map<String, String> DEFAULT_HEADERS_TO_SPLIT_ON = Map.of(
            "#", "h1",
            "##", "h2",
            "###", "h3",
            "####", "h4"
    );

    private final Map<String, String> headersToSplitOn;
    private final int maxChunkSize;
    private final int chunkOverlap;

    /**
     * 使用默认标题映射与最大切片 1000 字符创建切片器
     */
    public MarkdownHeaderSplitter() {
        this(DEFAULT_HEADERS_TO_SPLIT_ON, 1000, 100);
    }

    /**
     * 指定最大切片限制创建切片器
     *
     * @param maxChunkSize 章节最大字符数 (若超过则级联递归分块；传 0 或负数表示不限制)
     * @param chunkOverlap 级联分块时的重叠字符数
     */
    public MarkdownHeaderSplitter(int maxChunkSize, int chunkOverlap) {
        this(DEFAULT_HEADERS_TO_SPLIT_ON, maxChunkSize, chunkOverlap);
    }

    /**
     * 全参数构造函数
     *
     * @param headersToSplitOn 标题标记到元数据键的映射字典 (例如 "#" -> "h1")
     * @param maxChunkSize     单章节超长二次分块阈值 (<= 0 表示不限制)
     * @param chunkOverlap     二次分块重叠字符数 (必须 >= 0 且在 maxChunkSize > 0 时小于 maxChunkSize)
     */
    public MarkdownHeaderSplitter(Map<String, String> headersToSplitOn, int maxChunkSize, int chunkOverlap) {
        Assert.notEmpty(headersToSplitOn, "headersToSplitOn 不能为空");
        if (maxChunkSize > 0) {
            Assert.isTrue(chunkOverlap >= 0, "chunkOverlap 不能为负数");
            Assert.isTrue(chunkOverlap < maxChunkSize, "chunkOverlap 必须小于 maxChunkSize");
        }

        this.headersToSplitOn = new LinkedHashMap<>(headersToSplitOn);
        this.maxChunkSize = maxChunkSize;
        this.chunkOverlap = chunkOverlap;
    }

    @Override
    public List<TextSegment> split(String text, Metadata metadata) {
        if (!StringUtils.hasText(text)) {
            return Collections.emptyList();
        }

        List<TextSegment> preliminarySegments = new ArrayList<>();
        String[] lines = text.split("\r?\n");

        StringBuilder currentSection = new StringBuilder();
        Map<Integer, String> currentHeaders = new TreeMap<>();
        boolean inCodeBlock = false;

        for (String rawLine : lines) {
            String trimmedLine = rawLine.trim();

            // 1. 代码块围栏探测 (``` 或 ~~~)，代码块内部的 # 不作为标题处理
            if (trimmedLine.startsWith("```") || trimmedLine.startsWith("~~~")) {
                inCodeBlock = !inCodeBlock;
                appendLine(currentSection, rawLine);
                continue;
            }

            if (!inCodeBlock) {
                Matcher matcher = HEADER_PATTERN.matcher(trimmedLine);
                if (matcher.matches()) {
                    String hashMarks = matcher.group(1);
                    String headerText = matcher.group(2).trim();

                    if (headersToSplitOn.containsKey(hashMarks)) {
                        // 发现新受管标题：先归档上一章节内容
                        emitSection(preliminarySegments, currentSection, currentHeaders, metadata);

                        // 更新标题层级拓扑：移除等于或大于当前层级的旧标题
                        int level = hashMarks.length();
                        currentHeaders.keySet().removeIf(lvl -> lvl >= level);
                        currentHeaders.put(level, headerText);

                        // 标题行本身可追加到新章节开头作为语义上下文
                        currentSection.append(rawLine).append("\n");
                        continue;
                    }
                }
            }

            // 2. 普通内容行追加
            appendLine(currentSection, rawLine);
        }

        // 3. 归档末尾章节
        emitSection(preliminarySegments, currentSection, currentHeaders, metadata);

        // 4. 若配置了 maxChunkSize，对超长章节执行级联递归二次切片，否则直接使用初筛结果
        List<TextSegment> finalSegments = new ArrayList<>();
        RecursiveCharacterSplitter subSplitter = maxChunkSize > 0
                ? new RecursiveCharacterSplitter(maxChunkSize, chunkOverlap)
                : null;

        for (TextSegment seg : preliminarySegments) {
            if (subSplitter != null && seg.text().length() > maxChunkSize) {
                List<TextSegment> subSegments = subSplitter.split(seg.text(), seg.metadata());
                finalSegments.addAll(subSegments);
            } else {
                finalSegments.add(seg);
            }
        }

        // 5. 重新分配连续的 chunk_index 与 total_chunks
        int total = finalSegments.size();
        List<TextSegment> result = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            TextSegment original = finalSegments.get(i);
            Metadata segMeta = original.metadata().copy();
            segMeta.put("chunk_index", i);
            segMeta.put("total_chunks", total);
            segMeta.put("char_count", original.text().length());
            result.add(TextSegment.from(original.text(), segMeta));
        }

        return Collections.unmodifiableList(result);
    }

    private void appendLine(StringBuilder buffer, String line) {
        buffer.append(line).append("\n");
    }

    private void emitSection(List<TextSegment> segments,
                             StringBuilder currentSection,
                             Map<Integer, String> currentHeaders,
                             Metadata baseMetadata) {
        String content = currentSection.toString().trim();
        currentSection.setLength(0);

        if (content.isEmpty()) {
            return;
        }

        Metadata sectionMeta = baseMetadata != null ? baseMetadata.copy() : new Metadata();
        // 将当前祖先标题层级注入元数据
        for (Map.Entry<Integer, String> entry : currentHeaders.entrySet()) {
            String mark = "#".repeat(entry.getKey());
            String metaKey = headersToSplitOn.get(mark);
            if (metaKey != null) {
                sectionMeta.put(metaKey, entry.getValue());
            }
        }

        segments.add(TextSegment.from(content, sectionMeta));
    }

    public Map<String, String> getHeadersToSplitOn() {
        return headersToSplitOn;
    }

    public int getMaxChunkSize() {
        return maxChunkSize;
    }

    public int getChunkOverlap() {
        return chunkOverlap;
    }
}
