package io.github.atengk.boot.ai.rag.citation;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * RAG 证据链溯源与引用双向校验清洗器 (CitationExtractor)
 * 基于正则与双向指针对齐模型输出的 [N] 引用标记，自动清洗剔除越界非法引用并计算事实忠实度
 *
 * @author Ateng
 * @since 2026-11-06
 */
public final class CitationExtractor {

    private static final Logger log = LoggerFactory.getLogger(CitationExtractor.class);

    /**
     * 匹配形如 [1]、[1, 2]、[1，2]、[1、2] 的引用序号锚点正则模式
     */
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d+(?:\\s*[,，、]\\s*\\d+)*)\\]");

    /**
     * 序号分隔符匹配正则 (逗号、顿号、空格)
     */
    private static final Pattern DELIMITER_PATTERN = Pattern.compile("[,，、\\s]+");

    /**
     * 常见文档名称元数据键候选列表 (按优先级扫描)
     */
    private static final String[] DOC_NAME_KEYS = {
            "file_name", "doc_name", "document_name", "source", "filename", "title", "name"
    };

    /**
     * 常见切片 ID 元数据键候选列表
     */
    private static final String[] SEGMENT_ID_KEYS = {
            "id", "segment_id", "chunk_id", "uuid"
    };

    /**
     * 常见相似度得分元数据键候选列表
     */
    private static final String[] SCORE_KEYS = {
            "score", "similarity_score", "similarity"
    };

    private CitationExtractor() {
        // 工具类私有化构造方法
    }

    /**
     * 从模型原始生成文本中解析、对齐并清洗引用，组装为强类型 RagResponse
     *
     * @param rawText    大模型原始回答文本
     * @param candidates 召回的真实候选切片列表 (1-based index)
     * @return 包含清洗后正文、有效引用清单与忠实度评分的 RagResponse
     */
    public static RagResponse<String> extract(String rawText, List<TextSegment> candidates) {
        if (!StringUtils.hasText(rawText)) {
            return RagResponse.of("", Collections.emptyList(), 0.0);
        }

        List<TextSegment> safeCandidates = candidates != null ? candidates : Collections.emptyList();
        int candidateCount = safeCandidates.size();

        // 1. 双向扫描并提取有效与越界引用序号
        Set<Integer> validIndices = new LinkedHashSet<>();
        Set<Integer> invalidIndices = new LinkedHashSet<>();

        Matcher matcher = CITATION_PATTERN.matcher(rawText);
        while (matcher.find()) {
            String inner = matcher.group(1);
            String[] parts = DELIMITER_PATTERN.split(inner.trim());
            for (String part : parts) {
                if (!StringUtils.hasText(part)) {
                    continue;
                }
                try {
                    int index = Integer.parseInt(part.trim());
                    if (index >= 1 && index <= candidateCount) {
                        validIndices.add(index);
                    } else {
                        invalidIndices.add(index);
                    }
                } catch (NumberFormatException ignored) {
                    // 非法整型跳过
                }
            }
        }

        if (!invalidIndices.isEmpty()) {
            log.warn("[RAG-CITATION-CLEANUP] 检测到越界非法引用序号: {}, 当前候选总数: {}, 将自动执行清洗防卫",
                    invalidIndices, candidateCount);
        }

        // 2. 执行正文文本清洗：剔除超出合法候选范围的越界引用
        String sanitizedText = sanitizeText(rawText, candidateCount);

        // 3. 构建结构化 Citation 清单 (按序号升序排列)
        List<Citation> citations = new ArrayList<>();
        List<Integer> sortedIndices = validIndices.stream().sorted().toList();
        for (Integer idx : sortedIndices) {
            TextSegment seg = safeCandidates.get(idx - 1);
            citations.add(buildCitation(idx, seg));
        }

        // 4. 计算事实忠实度评分 (Grounding Score)
        int validCount = validIndices.size();
        int invalidCount = invalidIndices.size();
        double groundingScore;
        if (validCount + invalidCount == 0) {
            // 正文中完全未引用锚点：若候选为空则算作 1.0 (诚实作答)，若有候选但未引用则为 0.0
            groundingScore = candidateCount == 0 ? 1.0 : 0.0;
        } else {
            groundingScore = BigDecimal.valueOf((double) validCount / (validCount + invalidCount))
                    .setScale(2, RoundingMode.HALF_UP)
                    .doubleValue();
        }

        return RagResponse.of(sanitizedText, citations, groundingScore);
    }

    /**
     * 仅提取有效结构化 Citation 清单
     *
     * @param rawText    大模型原始回答文本
     * @param candidates 召回的真实候选切片列表
     * @return 有效结构化 Citation 列表 (保底返回空列表)
     */
    public static List<Citation> extractCitations(String rawText, List<TextSegment> candidates) {
        if (!StringUtils.hasText(rawText) || candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        return extract(rawText, candidates).getCitations();
    }

    /**
     * 执行文本清洗：扫描文本中的引用标记 [N]，剔除所有小于 1 或大于 candidateCount 的越界引用
     *
     * @param text           待清洗文本
     * @param candidateCount 真实候选切片总数
     * @return 清洗剔除越界引用后的安全文本
     */
    public static String sanitizeText(String text, int candidateCount) {
        if (!StringUtils.hasText(text)) {
            return text != null ? text : "";
        }

        Matcher matcher = CITATION_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String inner = matcher.group(1);
            String[] parts = DELIMITER_PATTERN.split(inner.trim());
            List<Integer> validInGroup = new ArrayList<>();
            for (String part : parts) {
                if (StringUtils.hasText(part)) {
                    try {
                        int val = Integer.parseInt(part.trim());
                        if (val >= 1 && val <= candidateCount) {
                            validInGroup.add(val);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }

            if (validInGroup.isEmpty()) {
                // 当前括号内所有序号均越界，整块替换为空字符串
                matcher.appendReplacement(sb, "");
            } else {
                // 保留合法序号并规范化重建为 [1, 2] 格式
                String replacement = "[" + validInGroup.stream()
                        .map(String::valueOf)
                        .collect(Collectors.joining(", ")) + "]";
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 构建带有显式引用指示的知识库问答 Prompt
     *
     * @param question   用户问题
     * @param candidates 知识库候选切片列表
     * @return 增强合成后的 Prompt
     */
    public static String buildPrompt(String question, List<TextSegment> candidates) {
        return buildPrompt(null, question, candidates);
    }

    /**
     * 构建带有显式引用指示与自定义系统说明的知识库问答 Prompt
     *
     * @param systemInstruction 自定义系统指令 (可选)
     * @param question          用户问题
     * @param candidates        知识库候选切片列表
     * @return 增强合成后的 Prompt
     */
    public static String buildPrompt(String systemInstruction, String question, List<TextSegment> candidates) {
        String baseInstruction = StringUtils.hasText(systemInstruction)
                ? systemInstruction.trim()
                : "你是一个专业的企业知识库问答助手。请严格基于以下编号参考资料准确回答用户的问题。\n" +
                "在回答时，必须在陈述事实的句子末尾显式标注所引用的参考资料序号锚点（例如：事实陈述[1]，复合事实[1, 2]）。\n" +
                "严禁捏造或引用不存在的资料序号。若参考资料未包含答案，请直接诚实回答\"参考资料中未包含相关信息\"。";

        if (candidates == null || candidates.isEmpty()) {
            return String.format("""
                    %s
                    
                    【参考资料列表】:
                    (暂无相关参考资料)
                    
                    【用户问题】: %s
                    """, baseInstruction, question != null ? question.trim() : "");
        }

        StringBuilder refBuilder = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            int index = i + 1;
            TextSegment seg = candidates.get(i);
            String docName = extractDocName(seg);
            String content = seg != null && seg.text() != null ? seg.text().trim() : "";
            refBuilder.append(String.format("[%d] (来源: %s) %s\n", index, docName, content));
        }

        return String.format("""
                %s
                
                【参考资料列表】:
                %s
                【用户问题】: %s
                """, baseInstruction, refBuilder.toString().trim(), question != null ? question.trim() : "");
    }

    /**
     * 将 LangChain4j 的 Content 列表转换为 TextSegment 列表
     *
     * @param contents Content 列表
     * @return TextSegment 列表 (保底返回空列表)
     */
    public static List<TextSegment> fromContents(List<Content> contents) {
        if (contents == null || contents.isEmpty()) {
            return Collections.emptyList();
        }
        return contents.stream()
                .map(Content::textSegment)
                .filter(Objects::nonNull)
                .toList();
    }

    private static Citation buildCitation(int index, TextSegment segment) {
        if (segment == null) {
            return Citation.builder()
                    .index(index)
                    .segmentId("seg-" + index)
                    .docName("未知文档")
                    .chunkContent("")
                    .similarityScore(1.0)
                    .metadata(Collections.emptyMap())
                    .build();
        }

        String segmentId = extractSegmentId(segment, index);
        String docName = extractDocName(segment);
        Double score = extractScore(segment);
        Map<String, Object> metaMap = segment.metadata() != null
                ? new LinkedHashMap<>(segment.metadata().toMap())
                : Collections.emptyMap();

        return Citation.builder()
                .index(index)
                .segmentId(segmentId)
                .docName(docName)
                .chunkContent(segment.text() != null ? segment.text() : "")
                .similarityScore(score)
                .metadata(metaMap)
                .build();
    }

    private static String extractDocName(TextSegment segment) {
        if (segment == null || segment.metadata() == null) {
            return "未知文档";
        }
        Map<String, Object> meta = segment.metadata().toMap();
        for (String key : DOC_NAME_KEYS) {
            Object val = meta.get(key);
            if (val != null && StringUtils.hasText(val.toString())) {
                return val.toString().trim();
            }
        }
        return "未知文档";
    }

    private static String extractSegmentId(TextSegment segment, int defaultIndex) {
        if (segment == null || segment.metadata() == null) {
            return "seg-" + defaultIndex;
        }
        Map<String, Object> meta = segment.metadata().toMap();
        for (String key : SEGMENT_ID_KEYS) {
            Object val = meta.get(key);
            if (val != null && StringUtils.hasText(val.toString())) {
                return val.toString().trim();
            }
        }
        return "seg-" + defaultIndex;
    }

    private static Double extractScore(TextSegment segment) {
        if (segment == null || segment.metadata() == null) {
            return 1.0;
        }
        Map<String, Object> meta = segment.metadata().toMap();
        for (String key : SCORE_KEYS) {
            Object val = meta.get(key);
            if (val instanceof Number num) {
                return num.doubleValue();
            } else if (val != null && StringUtils.hasText(val.toString())) {
                try {
                    return Double.parseDouble(val.toString().trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 1.0;
    }
}
