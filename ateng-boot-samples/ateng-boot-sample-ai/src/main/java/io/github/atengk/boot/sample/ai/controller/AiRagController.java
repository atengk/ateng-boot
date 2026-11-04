package io.github.atengk.boot.sample.ai.controller;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.github.atengk.boot.ai.template.AiTemplate;
import io.github.atengk.boot.ai.template.EmbeddingTemplate;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.ai.dto.AiRagAskRequest;
import io.github.atengk.boot.sample.ai.dto.AiRagEmbedRequest;
import io.github.atengk.boot.sample.ai.dto.AiRagIngestRequest;
import io.github.atengk.boot.sample.ai.dto.AiRagRetrieveRequest;
import io.github.atengk.boot.sample.ai.vo.AiRagAskVo;
import io.github.atengk.boot.sample.ai.vo.AiRagEmbedVo;
import io.github.atengk.boot.sample.ai.vo.AiRagIngestVo;
import io.github.atengk.boot.sample.ai.vo.AiRagRetrieveVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private final EmbeddingTemplate embeddingTemplate;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ContentRetriever contentRetriever;
    private final AiTemplate aiTemplate;

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

        // 2. 无匹配上下文时保底提示
        if (referenceContexts.isEmpty()) {
            AiRagAskVo vo = AiRagAskVo.builder()
                    .question(request.getQuestion())
                    .answer("知识库中暂未检索到与提问相关的参考知识片段。")
                    .referenceContexts(Collections.emptyList())
                    .build();
            return Result.success(vo);
        }

        // 3. 合成增强 Prompt
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

        // 4. 调用大模型生成严谨回答
        String answer = aiTemplate.chat(augmentedPrompt);

        AiRagAskVo vo = AiRagAskVo.builder()
                .question(request.getQuestion())
                .answer(answer)
                .referenceContexts(referenceContexts)
                .build();
        return Result.success(vo);
    }
}
