package io.github.atengk.boot.ai.rag.fusion;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion (RRF) 倒数排名融合算法器
 * <p>
 * 遵循现代混合检索 (Hybrid Search) 工业级行业标准，将稠密向量检索 (Dense Recall) 与稀疏关键词检索 (Sparse Recall)
 * 等多路异构召回结果进行无监督加权倒数排名融合：
 * <br>
 * 公式：{@code RRF_Score(d) = \sum_{m \in M} \frac{w_m}{k + rank_m(d)}}
 * <br>
 * 其中 {@code k} 为倒数平滑常数（行业最佳经验取值默认为 60），{@code rank_m(d)} 为文档在第 m 路召回中的位次 (1-based)。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public final class ReciprocalRankFuser {

    /**
     * 默认 RRF 倒数平滑常数 (Reciprocal Constant k = 60)
     */
    public static final int DEFAULT_RRF_K = 60;

    private ReciprocalRankFuser() {
    }

    /**
     * 携带 RRF 融合得分的检索内容不可变结果载体
     *
     * @param content  原始检索内容
     * @param rrfScore RRF 综合融合得分
     */
    public record ScoredContent(Content content, double rrfScore) {
    }

    /**
     * 对多路检索结果列表执行默认 RRF 融合去重与重排 (默认 k=60，等权重)
     *
     * @param rankedResultLists 多路有序检索候选集
     * @param topK              融合后最终保留的最大结果数量
     * @return 融合排序后的 Content 列表
     */
    public static List<Content> fuse(List<List<Content>> rankedResultLists, int topK) {
        return fuse(rankedResultLists, null, topK, DEFAULT_RRF_K);
    }

    /**
     * 对多路检索结果列表执行全参数加权 RRF 融合去重与重排
     *
     * @param rankedResultLists 多路有序检索候选集 (如 List[DenseResults, SparseResults])
     * @param weights           各路检索器对应的相对权重 (传 null 时默认每路权重均为 1.0)
     * @param topK              融合后最终保留的最大结果数量
     * @param rrfK              RRF 平滑常数 (建议取 60)
     * @return 融合排序后的 Content 列表
     */
    public static List<Content> fuse(List<List<Content>> rankedResultLists,
                                     List<Double> weights,
                                     int topK,
                                     int rrfK) {
        List<ScoredContent> scored = fuseWithScores(rankedResultLists, weights, topK, rrfK);
        return scored.stream().map(ScoredContent::content).toList();
    }

    /**
     * 对多路检索结果列表执行全参数加权 RRF 融合，并返回带有最终融合得分的详细列表
     *
     * @param rankedResultLists 多路有序检索候选集
     * @param weights           各路检索器对应的相对权重
     * @param topK              融合后最终保留的最大结果数量
     * @param rrfK              RRF 平滑常数
     * @return 融合排序后的 ScoredContent 列表
     */
    public static List<ScoredContent> fuseWithScores(List<List<Content>> rankedResultLists,
                                                     List<Double> weights,
                                                     int topK,
                                                     int rrfK) {
        if (rankedResultLists == null || rankedResultLists.isEmpty() || topK <= 0) {
            return Collections.emptyList();
        }

        int constantK = rrfK > 0 ? rrfK : DEFAULT_RRF_K;
        Map<String, Double> scoreMap = new HashMap<>();
        Map<String, Content> contentMap = new LinkedHashMap<>();

        for (int m = 0; m < rankedResultLists.size(); m++) {
            List<Content> resultList = rankedResultLists.get(m);
            if (resultList == null || resultList.isEmpty()) {
                continue;
            }

            double weight = (weights != null && weights.size() > m && weights.get(m) != null && weights.get(m) > 0)
                    ? weights.get(m)
                    : 1.0;

            for (int rank = 1; rank <= resultList.size(); rank++) {
                Content content = resultList.get(rank - 1);
                if (content == null) {
                    continue;
                }

                String docKey = extractDocumentKey(content);
                double contribution = weight / (constantK + rank);
                scoreMap.put(docKey, scoreMap.getOrDefault(docKey, 0.0) + contribution);
                contentMap.putIfAbsent(docKey, content);
            }
        }

        if (scoreMap.isEmpty()) {
            return Collections.emptyList();
        }

        List<ScoredContent> scoredList = new ArrayList<>(scoreMap.size());
        for (Map.Entry<String, Double> entry : scoreMap.entrySet()) {
            Content content = contentMap.get(entry.getKey());
            if (content != null) {
                scoredList.add(new ScoredContent(content, entry.getValue()));
            }
        }

        // 依据 RRF 得分降序排序
        scoredList.sort((a, b) -> Double.compare(b.rrfScore(), a.rrfScore()));

        if (scoredList.size() > topK) {
            return scoredList.subList(0, topK);
        }
        return scoredList;
    }

    /**
     * 提取文档切片的全局稳定唯一性标识键
     */
    public static String extractDocumentKey(Content content) {
        if (content == null) {
            return "";
        }
        TextSegment segment = content.textSegment();
        if (segment != null) {
            Metadata meta = segment.metadata();
            if (meta != null) {
                String docId = meta.getString("doc_id");
                String chunkIndex = meta.getString("chunk_index");
                if (docId != null && chunkIndex != null) {
                    return docId + "#" + chunkIndex;
                }
                String id = meta.getString("id");
                if (id != null) {
                    return id;
                }
            }
            if (segment.text() != null) {
                return DigestUtils.md5DigestAsHex(segment.text().getBytes(StandardCharsets.UTF_8));
            }
        }
        return String.valueOf(System.identityHashCode(content));
    }
}
