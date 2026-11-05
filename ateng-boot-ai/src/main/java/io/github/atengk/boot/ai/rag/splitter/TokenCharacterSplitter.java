package io.github.atengk.boot.ai.rag.splitter;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.Tokenizer;
import dev.langchain4j.model.openai.OpenAiTokenizer;
import io.github.atengk.boot.ai.memory.HeuristicTokenizer;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 原生基于 Token 预算防溢出智能切片器 (TokenCharacterSplitter)
 * <p>
 * 解决按字符切片在中文、特殊符号或代码造成 Token 膨胀导致下游 Embedding 模型（如 text-embedding-3-small 上限 8192 tokens）报 400 Bad Request 溢出的痛点。
 * 支持基于 LangChain4j {@link Tokenizer} 准确计量与安全边际（Safety Margin）弹性防卫，确保生成的每一个切片在 Token 维度绝对不超限。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public class TokenCharacterSplitter implements DocumentSplitter {

    public static final int DEFAULT_MAX_TOKENS = 500;
    public static final int DEFAULT_TOKEN_OVERLAP = 50;
    public static final double DEFAULT_SAFETY_MARGIN = 0.0;

    private final int maxTokens;
    private final int tokenOverlap;
    private final Tokenizer tokenizer;
    private final double safetyMargin;
    private final int effectiveMaxTokens;
    private final List<String> separators;
    private final boolean keepSeparators;

    /**
     * 使用默认参数构造 Token 切片器 (maxTokens=500, tokenOverlap=50, 默认 Tokenizer)
     */
    public TokenCharacterSplitter() {
        this(DEFAULT_MAX_TOKENS, DEFAULT_TOKEN_OVERLAP);
    }

    /**
     * 指定最大 Token 预算与重叠数构造切片器
     *
     * @param maxTokens    单切片最大允许的 Token 数量 (必须 > 0)
     * @param tokenOverlap 相邻切片重叠的 Token 数量 (必须 >= 0 且 < maxTokens)
     */
    public TokenCharacterSplitter(int maxTokens, int tokenOverlap) {
        this(maxTokens, tokenOverlap, null, DEFAULT_SAFETY_MARGIN);
    }

    /**
     * 指定最大 Token 预算、重叠数与分词器构造切片器
     *
     * @param maxTokens    单切片最大允许的 Token 数量 (必须 > 0)
     * @param tokenOverlap 相邻切片重叠的 Token 数量 (必须 >= 0 且 < maxTokens)
     * @param tokenizer    LangChain4j Tokenizer 分词器实例 (若为 null 则自动选用 OpenAiTokenizer 或 HeuristicTokenizer 保底)
     */
    public TokenCharacterSplitter(int maxTokens, int tokenOverlap, Tokenizer tokenizer) {
        this(maxTokens, tokenOverlap, tokenizer, DEFAULT_SAFETY_MARGIN);
    }

    /**
     * 指定 Token 预算、重叠数、分词器与安全边际构造切片器
     *
     * @param maxTokens    单切片最大允许的 Token 数量 (必须 > 0)
     * @param tokenOverlap 相邻切片重叠的 Token 数量 (必须 >= 0 且 < maxTokens)
     * @param tokenizer    分词器实例 (可为空)
     * @param safetyMargin 安全余量百分比 (0.0 ~ <1.0，如 0.1 表示预留 10% 缓冲预算)
     */
    public TokenCharacterSplitter(int maxTokens, int tokenOverlap, Tokenizer tokenizer, double safetyMargin) {
        this(maxTokens, tokenOverlap, tokenizer, safetyMargin, RecursiveCharacterSplitter.DEFAULT_SEPARATORS, true);
    }

    /**
     * 全参数构造函数
     *
     * @param maxTokens      单切片最大允许的 Token 数量 (必须 > 0)
     * @param tokenOverlap   相邻切片重叠的 Token 数量 (必须 >= 0 且 < maxTokens)
     * @param tokenizer      分词器实例 (可为空)
     * @param safetyMargin   安全余量百分比 (0.0 ~ <1.0)
     * @param separators     层级分隔符列表
     * @param keepSeparators 是否保留分隔符
     */
    public TokenCharacterSplitter(int maxTokens, int tokenOverlap, Tokenizer tokenizer, double safetyMargin,
                                  List<String> separators, boolean keepSeparators) {
        Assert.isTrue(maxTokens > 0, "maxTokens 必须大于 0");
        Assert.isTrue(tokenOverlap >= 0, "tokenOverlap 不能为负数");
        Assert.isTrue(tokenOverlap < maxTokens, "tokenOverlap 必须小于 maxTokens");
        Assert.isTrue(safetyMargin >= 0.0 && safetyMargin < 1.0, "safetyMargin 必须在 [0.0, 1.0) 范围内");
        Assert.notEmpty(separators, "separators 不能为空");

        this.maxTokens = maxTokens;
        this.tokenOverlap = tokenOverlap;
        this.tokenizer = resolveTokenizer(tokenizer);
        this.safetyMargin = safetyMargin;
        int budget = (int) Math.floor(maxTokens * (1.0 - safetyMargin));
        this.effectiveMaxTokens = Math.max(1, budget);
        this.separators = List.copyOf(separators);
        this.keepSeparators = keepSeparators;
    }

    @Override
    public List<TextSegment> split(String text, Metadata metadata) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        // 1. 若整段文本已在有效 Token 预算内，直接作为单片段返回
        int totalTokens = this.tokenizer.estimateTokenCountInText(text);
        if (totalTokens <= this.effectiveMaxTokens) {
            Metadata chunkMetadata = metadata != null ? metadata.copy() : new Metadata();
            chunkMetadata.put("chunk_index", 0);
            chunkMetadata.put("total_chunks", 1);
            chunkMetadata.put("char_count", text.length());
            chunkMetadata.put("token_count", totalTokens);
            return Collections.singletonList(TextSegment.from(text, chunkMetadata));
        }

        // 2. 递归将长文本根据层级分隔符拆分为粒度 <= effectiveMaxTokens 的子片段
        List<String> rawPieces = splitText(text, this.separators);

        // 3. 基于 Token 预算与 Token 重叠进行滑动窗口合并
        List<String> mergedChunks = mergePieces(rawPieces);

        // 4. 构建挂载 Token 计数与字符计数的 TextSegment 集合
        int total = mergedChunks.size();
        List<TextSegment> segments = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            String chunk = mergedChunks.get(i);
            int chunkTokenCount = this.tokenizer.estimateTokenCountInText(chunk);
            Metadata chunkMetadata = metadata != null ? metadata.copy() : new Metadata();
            chunkMetadata.put("chunk_index", i);
            chunkMetadata.put("total_chunks", total);
            chunkMetadata.put("char_count", chunk.length());
            chunkMetadata.put("token_count", chunkTokenCount);
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
            int tokenCount = this.tokenizer.estimateTokenCountInText(s);
            if (tokenCount <= this.effectiveMaxTokens) {
                finalPieces.add(s);
            } else {
                if (!nextSeps.isEmpty()) {
                    finalPieces.addAll(splitText(s, nextSeps));
                } else {
                    // 已至单字符/无分隔符级别，仍超限时使用二分法切割至 Token <= effectiveMaxTokens
                    finalPieces.addAll(sliceByTokens(s));
                }
            }
        }
        return finalPieces;
    }

    private List<String> sliceByTokens(String text) {
        List<String> result = new ArrayList<>();
        int start = 0;
        int len = text.length();

        while (start < len) {
            int low = start + 1;
            int high = len;
            int bestEnd = start + 1;

            while (low <= high) {
                int mid = (low + high) >>> 1;
                String sub = text.substring(start, mid);
                int tokens = this.tokenizer.estimateTokenCountInText(sub);
                if (tokens <= this.effectiveMaxTokens) {
                    bestEnd = mid;
                    low = mid + 1;
                } else {
                    high = mid - 1;
                }
            }

            result.add(text.substring(start, bestEnd));
            start = bestEnd;
        }

        return result;
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

        for (String piece : pieces) {
            if (piece.isEmpty()) {
                continue;
            }

            String candidateJoined = String.join("", currentDoc) + piece;
            int combinedTokens = this.tokenizer.estimateTokenCountInText(candidateJoined);

            if (combinedTokens > this.effectiveMaxTokens && !currentDoc.isEmpty()) {
                String chunk = String.join("", currentDoc).trim();
                if (!chunk.isEmpty()) {
                    addSafely(chunks, chunk);
                }

                // 滑动窗口剔除头部片段，确保：
                // 1. 剩余重叠片段 Token 计数 <= tokenOverlap
                // 2. 剩余片段与即将加入的新 piece 合并后总 Token 必须 <= effectiveMaxTokens
                while (!currentDoc.isEmpty()) {
                    String remaining = String.join("", currentDoc);
                    int remainingTokens = this.tokenizer.estimateTokenCountInText(remaining);
                    String testCandidate = remaining + piece;
                    int testTokens = this.tokenizer.estimateTokenCountInText(testCandidate);

                    if (remainingTokens <= this.tokenOverlap && testTokens <= this.effectiveMaxTokens) {
                        break;
                    }
                    currentDoc.remove(0);
                }
            }

            currentDoc.add(piece);
        }

        if (!currentDoc.isEmpty()) {
            String chunk = String.join("", currentDoc).trim();
            if (!chunk.isEmpty()) {
                addSafely(chunks, chunk);
            }
        }

        return chunks;
    }

    private void addSafely(List<String> chunks, String chunk) {
        int tokenCount = this.tokenizer.estimateTokenCountInText(chunk);
        if (tokenCount <= this.maxTokens) {
            chunks.add(chunk);
        } else {
            // 防御极端边界（如空格或标点合并导致 Token 偶然微量膨胀）
            chunks.addAll(sliceByTokens(chunk));
        }
    }

    @SuppressWarnings("deprecation")
    private static Tokenizer resolveTokenizer(Tokenizer customTokenizer) {
        if (customTokenizer != null) {
            return customTokenizer;
        }
        try {
            return new OpenAiTokenizer();
        } catch (Throwable t) {
            return new HeuristicTokenizer();
        }
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public int getTokenOverlap() {
        return tokenOverlap;
    }

    public Tokenizer getTokenizer() {
        return tokenizer;
    }

    public double getSafetyMargin() {
        return safetyMargin;
    }

    public int getEffectiveMaxTokens() {
        return effectiveMaxTokens;
    }

    public List<String> getSeparators() {
        return separators;
    }

    public boolean isKeepSeparators() {
        return keepSeparators;
    }
}
