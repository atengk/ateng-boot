package io.github.atengk.boot.ai.rag.fusion;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.filter.Filter;
import io.github.atengk.boot.ai.rag.filter.DynamicFilterContextHolder;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 原生轻量 BM25 稀疏关键词知识库检索器 (Bm25KeywordRetriever)
 * <p>
 * 纯原生标准库实现，无需引入外部重量级全文检索引擎。内置基于 {@link ReentrantLock} 并发受控的增量倒排索引
 * （支持按 {@code doc_id} 增删重建），兼容动态语料 {@link Supplier} 视图，并在检索期自动融合
 * {@link DynamicFilterContextHolder} 与租户上下文下推元数据过滤，严防跨租户稀疏召回越权。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public class Bm25KeywordRetriever implements ContentRetriever {

    private static final double DEFAULT_K1 = 1.2;
    private static final double DEFAULT_B = 0.75;
    private static final double MIN_SCORE_THRESHOLD = 0.0001;

    private final ReentrantLock lock = new ReentrantLock();
    private final Supplier<List<TextSegment>> segmentsSupplier;
    private final int maxResults;
    private final double k1;
    private final double b;

    private final CorpusIndex corpus = new CorpusIndex();
    private boolean statefulIndex;

    /**
     * 默认构造方法 (默认有状态空倒排索引，最大检索条数 10)
     */
    public Bm25KeywordRetriever() {
        this(10);
    }

    /**
     * 构造空语料的有状态增量倒排索引检索器
     *
     * @param maxResults 最大返回候选条数 (必须大于 0)
     */
    public Bm25KeywordRetriever(int maxResults) {
        Assert.isTrue(maxResults > 0, "maxResults 必须大于 0");
        this.segmentsSupplier = null;
        this.maxResults = maxResults;
        this.k1 = DEFAULT_K1;
        this.b = DEFAULT_B;
        this.statefulIndex = true;
    }

    /**
     * 基于初始静态切片列表构造有状态 BM25 检索器
     *
     * @param segments   初始文档切片集合
     * @param maxResults 最大返回候选条数 (必须大于 0)
     */
    public Bm25KeywordRetriever(List<TextSegment> segments, int maxResults) {
        this(maxResults);
        if (segments != null && !segments.isEmpty()) {
            this.corpus.addAll(segments);
        }
    }

    /**
     * 基于动态语料提供者构造 BM25 检索器
     *
     * @param segmentsSupplier 动态语料提供者 (不能为空)
     * @param maxResults       最大返回候选条数 (必须大于 0)
     */
    public Bm25KeywordRetriever(Supplier<List<TextSegment>> segmentsSupplier, int maxResults) {
        this(segmentsSupplier, maxResults, DEFAULT_K1, DEFAULT_B);
    }

    /**
     * 全参数构造方法
     *
     * @param segmentsSupplier 动态语料提供者 (不能为空)
     * @param maxResults       最大返回候选条数 (必须大于 0)
     * @param k1               BM25 词频饱和度调节参数 (非负)
     * @param b                BM25 文档长度归一化因子 ({@code [0.0, 1.0]})
     */
    public Bm25KeywordRetriever(Supplier<List<TextSegment>> segmentsSupplier,
                                int maxResults,
                                double k1,
                                double b) {
        Assert.notNull(segmentsSupplier, "segmentsSupplier 不能为空");
        Assert.isTrue(maxResults > 0, "maxResults 必须大于 0");
        Assert.isTrue(k1 >= 0.0, "k1 不能为负数");
        Assert.isTrue(b >= 0.0 && b <= 1.0, "b 必须介于 0.0 与 1.0 之间");
        this.segmentsSupplier = segmentsSupplier;
        this.maxResults = maxResults;
        this.k1 = k1;
        this.b = b;
        this.statefulIndex = false;
    }

    /**
     * 增量追加一批文档切片至倒排索引
     *
     * @param segments 新增切片集合 (null 或空集合直接忽略)
     */
    public void addSegments(List<TextSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return;
        }
        lock.lock();
        try {
            ensureStatefulInitializedLocked();
            corpus.addAll(segments);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 依据 {@code doc_id} 元数据增量剔除指定文档的全部历史切片
     *
     * @param docId 文档唯一业务标识
     * @return 实际被剔除的切片数
     */
    public int removeByDocId(String docId) {
        if (!StringUtils.hasText(docId)) {
            return 0;
        }
        String normalizedDocId = docId.trim();
        lock.lock();
        try {
            ensureStatefulInitializedLocked();
            return corpus.removeByDocId(normalizedDocId);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 全量清空并基于给定语料原子重建倒排索引
     *
     * @param segments 全新目标切片集合 (允许为 null 或空列表)
     */
    public void rebuild(List<TextSegment> segments) {
        lock.lock();
        try {
            this.statefulIndex = true;
            this.corpus.clear();
            if (segments != null && !segments.isEmpty()) {
                this.corpus.addAll(segments);
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 清空倒排索引中的全部语料与词项统计信息
     */
    public void clear() {
        lock.lock();
        try {
            this.statefulIndex = true;
            this.corpus.clear();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 获取当前检索器纳管的文档切片总数
     *
     * @return 当前索引切片总数
     */
    public int size() {
        boolean isStateful;
        lock.lock();
        try {
            isStateful = this.statefulIndex;
            if (isStateful) {
                return this.corpus.size();
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
        if (this.segmentsSupplier == null) {
            return 0;
        }
        List<TextSegment> dynamicSegments = this.segmentsSupplier.get();
        return dynamicSegments != null ? dynamicSegments.size() : 0;
    }

    @Override
    public List<Content> retrieve(Query query) {
        if (query == null || !StringUtils.hasText(query.text())) {
            return Collections.emptyList();
        }
        List<String> queryTokens = tokenize(query.text());
        if (queryTokens.isEmpty()) {
            return Collections.emptyList();
        }

        Filter activeFilter = DynamicFilterContextHolder.resolveFilter(null);

        boolean useStateful;
        lock.lock();
        try {
            useStateful = this.statefulIndex;
            if (useStateful) {
                return scoreAndRetrieve(this.corpus, queryTokens, activeFilter);
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }

        if (this.segmentsSupplier == null) {
            return Collections.emptyList();
        }
        List<TextSegment> dynamicSegments = this.segmentsSupplier.get();
        if (dynamicSegments == null || dynamicSegments.isEmpty()) {
            return Collections.emptyList();
        }
        CorpusIndex ephemeralIndex = new CorpusIndex();
        ephemeralIndex.addAll(dynamicSegments);
        return scoreAndRetrieve(ephemeralIndex, queryTokens, activeFilter);
    }

    private List<Content> scoreAndRetrieve(CorpusIndex targetIndex,
                                           List<String> queryTokens,
                                           Filter activeFilter) {
        int n = targetIndex.size();
        if (n == 0) {
            return Collections.emptyList();
        }
        double avgDocLength = targetIndex.totalDocLength() / (double) Math.max(1, n);
        double normAvgLen = Math.max(1.0, avgDocLength);

        Map<Integer, Double> docScores = new HashMap<>();
        Map<Integer, Boolean> filterDecisions = activeFilter != null ? new HashMap<>() : null;

        for (String qToken : queryTokens) {
            Map<Integer, Integer> posting = targetIndex.postings.get(qToken);
            if (posting == null || posting.isEmpty()) {
                continue;
            }
            int df = posting.size();
            // 标准 Lucene / BM25 IDF 公式: ln(1 + (N - df + 0.5) / (df + 0.5))
            double idf = Math.log(1.0 + (n - df + 0.5) / (df + 0.5));
            for (Map.Entry<Integer, Integer> entry : posting.entrySet()) {
                Integer seqId = entry.getKey();
                IndexedDoc doc = targetIndex.documents.get(seqId);
                if (doc == null) {
                    continue;
                }
                if (activeFilter != null) {
                    Boolean allowed = filterDecisions.get(seqId);
                    if (allowed == null) {
                        allowed = matchesFilter(doc.segment(), activeFilter);
                        filterDecisions.put(seqId, allowed);
                    }
                    if (!allowed) {
                        continue;
                    }
                }
                int tf = entry.getValue();
                double numerator = tf * (k1 + 1.0);
                double denominator = tf + k1 * (1.0 - b + b * (doc.docLength() / normAvgLen));
                double contribution = idf * (numerator / denominator);
                docScores.merge(seqId, contribution, Double::sum);
            }
        }

        if (docScores.isEmpty()) {
            return Collections.emptyList();
        }

        record ScoredSegment(int seqId, TextSegment segment, double score) {
        }

        List<ScoredSegment> scoredSegments = new ArrayList<>();
        for (Map.Entry<Integer, Double> entry : docScores.entrySet()) {
            double score = entry.getValue();
            if (score > MIN_SCORE_THRESHOLD) {
                IndexedDoc doc = targetIndex.documents.get(entry.getKey());
                if (doc != null) {
                    scoredSegments.add(new ScoredSegment(doc.seqId(), doc.segment(), score));
                }
            }
        }

        if (scoredSegments.isEmpty()) {
            return Collections.emptyList();
        }

        scoredSegments.sort((left, right) -> {
            int cmp = Double.compare(right.score(), left.score());
            return cmp != 0 ? cmp : Integer.compare(left.seqId(), right.seqId());
        });

        int limit = Math.min(maxResults, scoredSegments.size());
        return scoredSegments.subList(0, limit).stream()
                .map(s -> Content.from(s.segment()))
                .toList();
    }

    private void ensureStatefulInitializedLocked() {
        if (this.statefulIndex) {
            return;
        }
        this.statefulIndex = true;
        if (this.segmentsSupplier != null) {
            List<TextSegment> initial = this.segmentsSupplier.get();
            if (initial != null && !initial.isEmpty()) {
                this.corpus.addAll(initial);
            }
        }
    }

    private static boolean matchesFilter(TextSegment segment, Filter filter) {
        if (filter == null) {
            return true;
        }
        if (segment == null) {
            return false;
        }
        Metadata metadata = segment.metadata() != null ? segment.metadata() : new Metadata();
        try {
            return filter.test(metadata);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static String resolveDocId(TextSegment segment) {
        if (segment == null || segment.metadata() == null) {
            return null;
        }
        Map<String, Object> metaMap = segment.metadata().toMap();
        Object rawDocId = metaMap.get("doc_id");
        if (rawDocId == null) {
            rawDocId = metaMap.get("docId");
        }
        if (rawDocId == null) {
            return null;
        }
        String docId = String.valueOf(rawDocId).trim();
        return StringUtils.hasText(docId) ? docId : null;
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

    private record IndexedDoc(
            int seqId,
            String docId,
            TextSegment segment,
            int docLength,
            Map<String, Integer> termFreq
    ) {
    }

    private static final class CorpusIndex {

        private int nextSeqId = 0;
        private long totalDocLength = 0L;
        private final Map<Integer, IndexedDoc> documents = new LinkedHashMap<>();
        private final Map<String, Map<Integer, Integer>> postings = new HashMap<>();
        private final Map<String, Set<Integer>> docIdToSeqIds = new HashMap<>();

        void addAll(Collection<TextSegment> segments) {
            if (segments == null || segments.isEmpty()) {
                return;
            }
            for (TextSegment segment : segments) {
                if (segment == null) {
                    continue;
                }
                String text = segment.text() != null ? segment.text() : "";
                List<String> tokens = tokenize(text);
                Map<String, Integer> termFreq = new HashMap<>();
                for (String token : tokens) {
                    termFreq.merge(token, 1, Integer::sum);
                }

                int seqId = nextSeqId++;
                String docId = resolveDocId(segment);
                IndexedDoc doc = new IndexedDoc(
                        seqId,
                        docId,
                        segment,
                        tokens.size(),
                        Collections.unmodifiableMap(termFreq)
                );
                documents.put(seqId, doc);
                totalDocLength += tokens.size();

                if (docId != null) {
                    docIdToSeqIds.computeIfAbsent(docId, k -> new LinkedHashSet<>()).add(seqId);
                }
                for (Map.Entry<String, Integer> tfEntry : termFreq.entrySet()) {
                    postings.computeIfAbsent(tfEntry.getKey(), k -> new LinkedHashMap<>())
                            .put(seqId, tfEntry.getValue());
                }
            }
        }

        int removeByDocId(String docId) {
            Set<Integer> seqIds = docIdToSeqIds.remove(docId);
            if (seqIds == null || seqIds.isEmpty()) {
                return 0;
            }
            int removed = 0;
            for (Integer seqId : seqIds) {
                IndexedDoc removedDoc = documents.remove(seqId);
                if (removedDoc == null) {
                    continue;
                }
                removed++;
                totalDocLength -= removedDoc.docLength();
                for (String term : removedDoc.termFreq().keySet()) {
                    Map<Integer, Integer> posting = postings.get(term);
                    if (posting != null) {
                        posting.remove(seqId);
                        if (posting.isEmpty()) {
                            postings.remove(term);
                        }
                    }
                }
            }
            return removed;
        }

        void clear() {
            nextSeqId = 0;
            totalDocLength = 0L;
            documents.clear();
            postings.clear();
            docIdToSeqIds.clear();
        }

        int size() {
            return documents.size();
        }

        long totalDocLength() {
            return totalDocLength;
        }
    }
}
