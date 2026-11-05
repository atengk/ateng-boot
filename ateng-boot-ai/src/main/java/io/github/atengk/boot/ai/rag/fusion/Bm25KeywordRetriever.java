package io.github.atengk.boot.ai.rag.fusion;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 原生轻量 BM25 稀疏关键词知识库检索器 (Bm25KeywordRetriever)
 * <p>
 * 纯原生标准库实现，无需引入外部重量级全文检索引擎。采用标准 BM25 相关度排序模型
 * （{@code k1=1.2, b=0.75}）计算查询词与文本切片的词频逆文档频率相关度，可作为 {@link HybridContentRetriever}
 * 的稀疏召回分路或 100% 离线自洽混合检索组件。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public class Bm25KeywordRetriever implements ContentRetriever {

    private static final double DEFAULT_K1 = 1.2;
    private static final double DEFAULT_B = 0.75;

    private final Supplier<List<TextSegment>> segmentsSupplier;
    private final int maxResults;
    private final double k1;
    private final double b;

    public Bm25KeywordRetriever(List<TextSegment> segments, int maxResults) {
        this(() -> segments, maxResults, DEFAULT_K1, DEFAULT_B);
    }

    public Bm25KeywordRetriever(Supplier<List<TextSegment>> segmentsSupplier, int maxResults) {
        this(segmentsSupplier, maxResults, DEFAULT_K1, DEFAULT_B);
    }

    public Bm25KeywordRetriever(Supplier<List<TextSegment>> segmentsSupplier,
                                int maxResults,
                                double k1,
                                double b) {
        Assert.notNull(segmentsSupplier, "segmentsSupplier 不能为空");
        Assert.isTrue(maxResults > 0, "maxResults 必须大于 0");
        this.segmentsSupplier = segmentsSupplier;
        this.maxResults = maxResults;
        this.k1 = k1;
        this.b = b;
    }

    @Override
    public List<Content> retrieve(Query query) {
        if (query == null || !StringUtils.hasText(query.text())) {
            return Collections.emptyList();
        }

        List<TextSegment> allSegments = segmentsSupplier.get();
        if (allSegments == null || allSegments.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> queryTokens = tokenize(query.text());
        if (queryTokens.isEmpty()) {
            return Collections.emptyList();
        }

        int n = allSegments.size();
        List<List<String>> docTokensList = new ArrayList<>(n);
        double totalDocLength = 0;
        Map<String, Integer> docFreq = new HashMap<>();

        for (TextSegment segment : allSegments) {
            String text = segment != null && segment.text() != null ? segment.text() : "";
            List<String> tokens = tokenize(text);
            docTokensList.add(tokens);
            totalDocLength += tokens.size();

            Set<String> uniqueTokens = new HashSet<>(tokens);
            for (String token : uniqueTokens) {
                docFreq.put(token, docFreq.getOrDefault(token, 0) + 1);
            }
        }

        double avgDocLength = totalDocLength / (double) Math.max(1, n);

        record ScoredSegment(TextSegment segment, double score) {
        }

        List<ScoredSegment> scoredSegments = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            TextSegment segment = allSegments.get(i);
            List<String> tokens = docTokensList.get(i);
            int docLen = tokens.size();

            Map<String, Integer> termFreq = new HashMap<>();
            for (String t : tokens) {
                termFreq.put(t, termFreq.getOrDefault(t, 0) + 1);
            }

            double score = 0.0;
            for (String qToken : queryTokens) {
                int tf = termFreq.getOrDefault(qToken, 0);
                if (tf == 0) {
                    continue;
                }
                int df = docFreq.getOrDefault(qToken, 0);
                // 标准 Lucene / BM25 IDF 公式: ln(1 + (N - df + 0.5) / (df + 0.5))
                double idf = Math.log(1.0 + (n - df + 0.5) / (df + 0.5));
                double numerator = tf * (k1 + 1.0);
                double denominator = tf + k1 * (1.0 - b + b * (docLen / Math.max(1.0, avgDocLength)));
                score += idf * (numerator / denominator);
            }

            if (score > 0.0001) {
                scoredSegments.add(new ScoredSegment(segment, score));
            }
        }

        scoredSegments.sort((a, bVal) -> Double.compare(bVal.score(), a.score()));

        int limit = Math.min(maxResults, scoredSegments.size());
        return scoredSegments.subList(0, limit).stream()
                .map(s -> Content.from(s.segment()))
                .toList();
    }

    /**
     * 轻量分词器：支持中英文标点拆分与中文字符单字/双字切分
     */
    static List<String> tokenize(String text) {
        if (!StringUtils.hasText(text)) {
            return Collections.emptyList();
        }
        List<String> tokens = new ArrayList<>();
        String normalized = text.toLowerCase(Locale.ROOT);
        StringBuilder word = new StringBuilder();

        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (isCjk(c)) {
                    if (!word.isEmpty()) {
                        tokens.add(word.toString());
                        word.setLength(0);
                    }
                    tokens.add(String.valueOf(c));
                    if (i + 1 < normalized.length() && isCjk(normalized.charAt(i + 1))) {
                        tokens.add("" + c + normalized.charAt(i + 1));
                    }
                } else {
                    word.append(c);
                }
            } else {
                if (!word.isEmpty()) {
                    tokens.add(word.toString());
                    word.setLength(0);
                }
            }
        }
        if (!word.isEmpty()) {
            tokens.add(word.toString());
        }
        return tokens;
    }

    private static boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A;
    }
}
