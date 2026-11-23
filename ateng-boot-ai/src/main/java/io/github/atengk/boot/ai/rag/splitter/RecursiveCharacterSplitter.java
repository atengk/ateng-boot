package io.github.atengk.boot.ai.rag.splitter;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 原生递归字符智能切片器
 * <p>
 * 按照段落、换行、中英文标点、空格等优先级层次递归切分文本，
 * 并在片段间维护配置的字符重叠（Overlap），保障语义连续性与零外部重型依赖。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public class RecursiveCharacterSplitter implements DocumentSplitter {

    public static final int DEFAULT_CHUNK_SIZE = 500;
    public static final int DEFAULT_CHUNK_OVERLAP = 50;

    /**
     * 默认按层次递归切分的分隔符列表 (兼顾中英文段落、标点与单字边界)
     */
    public static final List<String> DEFAULT_SEPARATORS = List.of(
            "\n\n",
            "\n",
            "。",
            "！",
            "？",
            ".",
            "!",
            "?",
            "；",
            ";",
            " ",
            ""
    );

    private final int chunkSize;
    private final int chunkOverlap;
    private final List<String> separators;
    private final boolean keepSeparators;

    /**
     * 使用默认参数创建切片器 (chunkSize=500, chunkOverlap=50)
     */
    public RecursiveCharacterSplitter() {
        this(DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP);
    }

    /**
     * 使用指定切片与重叠大小创建切片器
     *
     * @param chunkSize    单个切片最大字符数 (必须 > 0)
     * @param chunkOverlap 相邻切片重叠字符数 (必须 >= 0 且 < chunkSize)
     */
    public RecursiveCharacterSplitter(int chunkSize, int chunkOverlap) {
        this(chunkSize, chunkOverlap, DEFAULT_SEPARATORS, true);
    }

    /**
     * 使用指定切片大小、重叠大小与自定义分隔符创建切片器
     *
     * @param chunkSize      单个切片最大字符数 (必须 > 0)
     * @param chunkOverlap   相邻切片重叠字符数 (必须 >= 0 且 < chunkSize)
     * @param separators     层级分隔符列表
     * @param keepSeparators 是否保留分隔符
     */
    public RecursiveCharacterSplitter(int chunkSize, int chunkOverlap, List<String> separators, boolean keepSeparators) {
        Assert.isTrue(chunkSize > 0, "chunkSize 必须大于 0");
        Assert.isTrue(chunkOverlap >= 0, "chunkOverlap 不能为负数");
        Assert.isTrue(chunkOverlap < chunkSize, "chunkOverlap 必须小于 chunkSize");
        Assert.notEmpty(separators, "separators 不能为空");

        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
        this.separators = List.copyOf(separators);
        this.keepSeparators = keepSeparators;
    }

    @Override
    public List<TextSegment> split(String text, Metadata metadata) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        // 1. 递归将长文本根据层级分隔符拆为粒度 <= chunkSize 的子片段
        List<String> rawPieces = splitText(text, this.separators);

        // 2. 根据 chunkSize 与 chunkOverlap 进行滑动窗口合并
        List<String> mergedChunks = mergePieces(rawPieces);

        // 3. 构建带元数据的 TextSegment 结果集
        int total = mergedChunks.size();
        List<TextSegment> segments = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            String chunk = mergedChunks.get(i);
            Metadata chunkMetadata = metadata != null ? metadata.copy() : new Metadata();
            chunkMetadata.put("chunk_index", i);
            chunkMetadata.put("total_chunks", total);
            chunkMetadata.put("char_count", chunk.length());
            segments.add(TextSegment.from(chunk, chunkMetadata));
        }

        return Collections.unmodifiableList(segments);
    }

    private List<String> splitText(String text, List<String> seps) {
        List<String> finalPieces = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return finalPieces;
        }

        String separator = seps.get(seps.size() - 1);
        List<String> nextSeps = Collections.emptyList();
        for (int i = 0; i < seps.size(); i++) {
            String sep = seps.get(i);
            if (sep.isEmpty() || text.contains(sep)) {
                separator = sep;
                nextSeps = seps.subList(i + 1, seps.size());
                break;
            }
        }

        List<String> splits = splitOnSeparator(text, separator);
        for (String s : splits) {
            if (s.isEmpty()) {
                continue;
            }
            if (s.length() <= chunkSize) {
                finalPieces.add(s);
            } else {
                if (!nextSeps.isEmpty()) {
                    finalPieces.addAll(splitText(s, nextSeps));
                } else {
                    for (int j = 0; j < s.length(); j += chunkSize) {
                        finalPieces.add(s.substring(j, Math.min(j + chunkSize, s.length())));
                    }
                }
            }
        }
        return finalPieces;
    }

    private List<String> splitOnSeparator(String text, String separator) {
        List<String> splits = new ArrayList<>();
        if (separator.isEmpty()) {
            for (int i = 0; i < text.length(); i++) {
                splits.add(String.valueOf(text.charAt(i)));
            }
            return splits;
        }

        int start = 0;
        int index;
        while ((index = text.indexOf(separator, start)) != -1) {
            if (keepSeparators) {
                splits.add(text.substring(start, index + separator.length()));
            } else {
                splits.add(text.substring(start, index));
            }
            start = index + separator.length();
        }
        if (start < text.length()) {
            splits.add(text.substring(start));
        }
        return splits;
    }

    private List<String> mergePieces(List<String> pieces) {
        List<String> chunks = new ArrayList<>();
        List<String> currentDoc = new ArrayList<>();
        int currentDocLen = 0;

        for (String piece : pieces) {
            if (piece.isEmpty()) {
                continue;
            }

            if (currentDocLen + piece.length() > chunkSize && !currentDoc.isEmpty()) {
                String chunk = String.join("", currentDoc).trim();
                if (!chunk.isEmpty()) {
                    chunks.add(chunk);
                }

                while ((currentDocLen > chunkOverlap && currentDoc.size() > 1)
                        || (currentDocLen + piece.length() > chunkSize && currentDocLen > 0)) {
                    String popped = currentDoc.remove(0);
                    currentDocLen -= popped.length();
                }
            }

            currentDoc.add(piece);
            currentDocLen += piece.length();
        }

        if (!currentDoc.isEmpty()) {
            String chunk = String.join("", currentDoc).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
        }

        return chunks;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public int getChunkOverlap() {
        return chunkOverlap;
    }

    public List<String> getSeparators() {
        return separators;
    }

    public boolean isKeepSeparators() {
        return keepSeparators;
    }
}
