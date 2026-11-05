package io.github.atengk.boot.sample.ai.controller;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.github.atengk.boot.ai.rag.parser.ParsedDocument;
import io.github.atengk.boot.ai.rag.query.QueryTransformer;
import io.github.atengk.boot.ai.rag.rerank.RerankResult;
import io.github.atengk.boot.ai.rag.rerank.Reranker;
import io.github.atengk.boot.ai.rag.splitter.DocumentSplitter;
import io.github.atengk.boot.ai.rag.splitter.MarkdownHeaderSplitter;
import io.github.atengk.boot.ai.rag.splitter.RecursiveCharacterSplitter;
import io.github.atengk.boot.ai.template.AiTemplate;
import io.github.atengk.boot.ai.template.EmbeddingTemplate;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.ai.dto.AiRagAskRequest;
import io.github.atengk.boot.sample.ai.dto.AiRagEmbedRequest;
import io.github.atengk.boot.sample.ai.dto.AiRagIngestRequest;
import io.github.atengk.boot.sample.ai.dto.AiRagRerankRequest;
import io.github.atengk.boot.sample.ai.dto.AiRagRetrieveRequest;
import io.github.atengk.boot.sample.ai.dto.AiRagSplitRequest;
import io.github.atengk.boot.sample.ai.dto.AiRagTransformRequest;
import io.github.atengk.boot.sample.ai.vo.AiRagAskVo;
import io.github.atengk.boot.sample.ai.vo.AiRagEmbedVo;
import io.github.atengk.boot.sample.ai.vo.AiRagIngestVo;
import io.github.atengk.boot.sample.ai.vo.AiRagRerankVo;
import io.github.atengk.boot.sample.ai.vo.AiRagRetrieveVo;
import io.github.atengk.boot.sample.ai.vo.AiRagSplitVo;
import io.github.atengk.boot.sample.ai.vo.AiRagTransformVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * AI RAG 企业知识库与向量检索体验接口
 *
 * @author Ateng
 * @since 2026-11-04
 */
@RestController
@RequestMapping("/sample/ai/rag")
@RequiredArgsConstructor
public class AiRagController {

    private static final Pattern MARKDOWN_IMAGE_PATTERN = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)]+)\\)");

    private final EmbeddingTemplate embeddingTemplate;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ContentRetriever contentRetriever;
    private final AiTemplate aiTemplate;
    private final ObjectProvider<DocumentSplitter> documentSplitterProvider;
    private final ObjectProvider<Reranker> rerankerProvider;
    private final ObjectProvider<QueryTransformer> queryTransformerProvider;

    /**
     * 长文档智能结构化切分体验
     *
     * @param request 文本切分入参
     * @return 分块结果与元数据详情
     */
    @PostMapping("/split")
    public Result<AiRagSplitVo> split(@Valid @RequestBody AiRagSplitRequest request) {
        DocumentSplitter splitter;
        String strategy = request.getStrategy() != null ? request.getStrategy().trim().toUpperCase() : "RECURSIVE";
        int chunkSize = request.getChunkSize() != null && request.getChunkSize() > 0 ? request.getChunkSize() : 500;
        int chunkOverlap = request.getChunkOverlap() != null && request.getChunkOverlap() >= 0 ? request.getChunkOverlap() : 50;

        if ("MARKDOWN".equals(strategy)) {
            splitter = new MarkdownHeaderSplitter(chunkSize, chunkOverlap);
        } else if (request.getChunkSize() != null || request.getChunkOverlap() != null) {
            splitter = new RecursiveCharacterSplitter(chunkSize, chunkOverlap);
        } else {
            splitter = documentSplitterProvider.getIfAvailable(() -> new RecursiveCharacterSplitter(chunkSize, chunkOverlap));
        }

        List<TextSegment> segments = splitter.split(request.getText());
        List<AiRagSplitVo.SegmentItem> segmentItems = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            TextSegment seg = segments.get(i);
            Map<String, Object> metadata = seg.metadata() != null ? new LinkedHashMap<>(seg.metadata().toMap()) : Collections.emptyMap();
            segmentItems.add(AiRagSplitVo.SegmentItem.builder()
                    .index(i)
                    .text(seg.text())
                    .characterCount(seg.text() != null ? seg.text().length() : 0)
                    .metadata(metadata)
                    .build());
        }

        AiRagSplitVo vo = AiRagSplitVo.builder()
                .totalSegments(segmentItems.size())
                .strategy(strategy)
                .segments(segmentItems)
                .build();
        return Result.success(vo);
    }

    /**
     * 知识库候选检索片段两阶段交叉重排体验
     *
     * @param request 重排入参 (query 与 candidates)
     * @return 经过语义精排的高相关度列表
     */
    @PostMapping("/rerank")
    public Result<AiRagRerankVo> rerank(@Valid @RequestBody AiRagRerankRequest request) {
        List<String> candidates = request.getCandidates();
        int topK = request.getTopK() != null && request.getTopK() > 0 ? request.getTopK() : 3;

        List<TextSegment> segments = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            segments.add(TextSegment.from(candidates.get(i), Metadata.from(Map.of("original_index", i))));
        }

        Reranker reranker = rerankerProvider.getIfAvailable();
        List<AiRagRerankVo.RankedItem> rankedItems = new ArrayList<>();

        if (reranker != null) {
            List<RerankResult> results = reranker.rerank(request.getQuery(), segments, topK);
            for (int rank = 0; rank < results.size(); rank++) {
                RerankResult res = results.get(rank);
                rankedItems.add(AiRagRerankVo.RankedItem.builder()
                        .rank(rank + 1)
                        .originalIndex(res.index())
                        .score(res.score() != null ? res.score() : 0.0)
                        .text(res.segment() != null ? res.segment().text() : "")
                        .build());
            }
        } else {
            // 本地启发式重排兜底 (根据关键词出现频率与子串匹配度打分，确保离线环境 100% 自洽可用)
            String query = request.getQuery().toLowerCase();
            String[] tokens = query.split("\\s+");
            List<RerankResult> localResults = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                TextSegment seg = segments.get(i);
                String text = seg.text();
                String textLower = text.toLowerCase();
                double score = 0.0;
                for (String token : tokens) {
                    if (token.isBlank()) continue;
                    if (textLower.contains(token)) {
                        score += 1.0;
                    }
                }
                localResults.add(new RerankResult(seg, i, score));
            }
            localResults.sort((a, b) -> Double.compare(b.score(), a.score()));
            int limit = Math.min(topK, localResults.size());
            for (int rank = 0; rank < limit; rank++) {
                RerankResult res = localResults.get(rank);
                rankedItems.add(AiRagRerankVo.RankedItem.builder()
                        .rank(rank + 1)
                        .originalIndex(res.index())
                        .score(res.score() != null ? res.score() : 0.0)
                        .text(res.segment() != null ? res.segment().text() : "")
                        .build());
            }
        }

        AiRagRerankVo vo = AiRagRerankVo.builder()
                .query(request.getQuery())
                .topK(rankedItems.size())
                .results(rankedItems)
                .build();
        return Result.success(vo);
    }

    /**
     * 多轮对话指代消解与语义检索查询改写体验
     *
     * @param request 查询改写参数
     * @return 独立完整检索 Query 与改写详情
     */
    @PostMapping("/transform")
    public Result<AiRagTransformVo> transform(@Valid @RequestBody AiRagTransformRequest request) {
        String rawQuery = request.getQuery();
        List<String> history = request.getHistory() != null ? request.getHistory() : Collections.emptyList();

        List<ChatMessage> chatMessages = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            String text = history.get(i);
            if (i % 2 == 0) {
                chatMessages.add(UserMessage.from(text));
            } else {
                chatMessages.add(AiMessage.from(text));
            }
        }

        QueryTransformer transformer = queryTransformerProvider.getIfAvailable();
        String transformedText = rawQuery;
        boolean rewritten = false;

        if (transformer != null && !chatMessages.isEmpty()) {
            Query transformed = transformer.transform(Query.from(rawQuery), chatMessages);
            if (transformed != null && StringUtils.hasText(transformed.text())) {
                transformedText = transformed.text();
                rewritten = !transformedText.trim().equalsIgnoreCase(rawQuery.trim());
            }
        }

        AiRagTransformVo vo = AiRagTransformVo.builder()
                .originalQuery(rawQuery)
                .transformedQuery(transformedText)
                .historyCount(chatMessages.size())
                .hasRewritten(rewritten)
                .message(rewritten ? "查询改写成功，已结合上下文消除代词指代" : "无需改写或未配置改写器，原样返回")
                .build();
        return Result.success(vo);
    }

    /**
     * 单文本向量化计算体验
     *
     * @param request 向量化入参
     * @return 向量维度与前 5 维浮点数预览
     */
    @PostMapping("/embed")
    public Result<AiRagEmbedVo> embed(@Valid @RequestBody AiRagEmbedRequest request) {
        Response<Embedding> response = embeddingTemplate.embed(request.getText());
        Embedding embedding = response.content();
        float[] vector = embedding != null ? embedding.vector() : new float[0];

        List<Float> preview = new ArrayList<>();
        for (int i = 0; i < Math.min(5, vector.length); i++) {
            preview.add(vector[i]);
        }

        AiRagEmbedVo vo = AiRagEmbedVo.builder()
                .dimension(vector.length)
                .preview(preview)
                .summary("成功生成 " + vector.length + " 维稠密特征向量")
                .build();
        return Result.success(vo);
    }

    /**
     * 知识库切片内容入库体验 (写入向量存储库)
     *
     * @param request 入库文本与元数据
     * @return 入库生成的记录 ID
     */
    @PostMapping("/ingest")
    public Result<AiRagIngestVo> ingest(@Valid @RequestBody AiRagIngestRequest request) {
        TextSegment segment;
        if (request.getMetadata() != null && !request.getMetadata().isEmpty()) {
            segment = TextSegment.from(request.getText(), Metadata.from(request.getMetadata()));
        } else {
            segment = TextSegment.from(request.getText());
        }

        String id = embeddingTemplate.ingest(embeddingStore, segment);

        AiRagIngestVo vo = AiRagIngestVo.builder()
                .id(id)
                .text(request.getText())
                .metadata(request.getMetadata() != null ? request.getMetadata() : Collections.emptyMap())
                .build();
        return Result.success(vo);
    }

    /**
     * 知识库向量相似度检索召回体验
     *
     * @param request 检索关键字
     * @return 命中的相关切片列表
     */
    @PostMapping("/retrieve")
    public Result<AiRagRetrieveVo> retrieve(@Valid @RequestBody AiRagRetrieveRequest request) {
        List<Content> contents = contentRetriever.retrieve(Query.from(request.getQuery()));
        List<AiRagRetrieveVo.MatchItem> matches = contents.stream()
                .map(content -> {
                    TextSegment segment = content.textSegment();
                    String text = segment != null ? segment.text() : "";
                    Map<String, String> metadata = Collections.emptyMap();
                    if (segment != null && segment.metadata() != null) {
                        metadata = new LinkedHashMap<>();
                        for (Map.Entry<String, Object> entry : segment.metadata().toMap().entrySet()) {
                            metadata.put(entry.getKey(), String.valueOf(entry.getValue()));
                        }
                    }
                    return AiRagRetrieveVo.MatchItem.builder()
                            .text(text)
                            .metadata(metadata)
                            .build();
                })
                .toList();

        AiRagRetrieveVo vo = AiRagRetrieveVo.builder()
                .query(request.getQuery())
                .matches(matches)
                .build();
        return Result.success(vo);
    }

    /**
     * RAG 知识库问答增强体验 (检索召回知识切片并合成 Prompt 驱动大模型精确作答)
     *
     * @param request 问答提问
     * @return 大模型融合知识库上下文生成的回答与参考来源
     */
    @PostMapping("/ask")
    public Result<AiRagAskVo> ask(@Valid @RequestBody AiRagAskRequest request) {
        // 1. 知识库向量相似度检索召回
        List<Content> contents = contentRetriever.retrieve(Query.from(request.getQuestion()));
        List<String> referenceContexts = contents.stream()
                .map(c -> c.textSegment() != null ? c.textSegment().text() : "")
                .filter(s -> !s.isBlank())
                .toList();

        // 2. 提取多模态图片直链 (从切片元数据及切片文本中提取)
        List<String> imageUrls = new ArrayList<>();
        for (Content c : contents) {
            TextSegment seg = c.textSegment();
            if (seg == null) {
                continue;
            }
            if (seg.metadata() != null) {
                Map<String, Object> metaMap = seg.metadata().toMap();
                Object urlsObj = metaMap.get(ParsedDocument.METADATA_IMAGE_URLS);
                if (urlsObj instanceof Iterable<?> iter) {
                    for (Object item : iter) {
                        if (item != null && StringUtils.hasText(item.toString())) {
                            String u = item.toString().trim();
                            if (!imageUrls.contains(u)) {
                                imageUrls.add(u);
                            }
                        }
                    }
                }
                Object singleObj = metaMap.get(ParsedDocument.METADATA_IMAGE_URL);
                if (singleObj != null && StringUtils.hasText(singleObj.toString())) {
                    String u = singleObj.toString().trim();
                    if (!imageUrls.contains(u)) {
                        imageUrls.add(u);
                    }
                }
            }
            if (StringUtils.hasText(seg.text())) {
                Matcher matcher = MARKDOWN_IMAGE_PATTERN.matcher(seg.text());
                while (matcher.find()) {
                    String u = matcher.group(2).trim();
                    if (StringUtils.hasText(u) && !imageUrls.contains(u)) {
                        imageUrls.add(u);
                    }
                }
            }
        }

        // 3. 无匹配上下文时保底提示
        if (referenceContexts.isEmpty()) {
            AiRagAskVo vo = AiRagAskVo.builder()
                    .question(request.getQuestion())
                    .answer("知识库中暂未检索到与提问相关的参考知识片段。")
                    .referenceContexts(Collections.emptyList())
                    .imageUrls(Collections.emptyList())
                    .build();
            return Result.success(vo);
        }

        // 4. 合成增强 Prompt
        String contextText = referenceContexts.stream()
                .map(ctx -> "- " + ctx)
                .collect(Collectors.joining("\n"));

        String augmentedPrompt = String.format("""
                你是一个专业的企业知识库问答助手。请严格基于以下已知参考资料准确回答用户的问题。
                如果提供的参考资料未包含答案，请直接诚实回答"参考资料中未包含相关信息"，坚决杜绝捏造。
                
                【知识库参考资料】:
                %s
                
                【用户问题】: %s
                """, contextText, request.getQuestion());

        // 5. 调用大模型生成严谨回答
        String answer = aiTemplate.chat(augmentedPrompt);

        AiRagAskVo vo = AiRagAskVo.builder()
                .question(request.getQuestion())
                .answer(answer)
                .referenceContexts(referenceContexts)
                .imageUrls(imageUrls)
                .build();
        return Result.success(vo);
    }
}
