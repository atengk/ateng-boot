package io.github.atengk.boot.sample.ai.controller;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.output.Response;
import io.github.atengk.boot.ai.registry.EmbeddingModelRegistry;
import io.github.atengk.boot.ai.template.EmbeddingTemplate;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.ai.dto.AiRagMultiModelEmbedRequest;
import io.github.atengk.boot.sample.ai.vo.AiRagMultiModelEmbedVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * AI RAG 多模型命名向量化计算与维度自省接口
 *
 * @author Ateng
 * @since 2026-11-05
 */
@RestController
@RequestMapping("/sample/ai/rag/embed")
@RequiredArgsConstructor
public class AiRagMultiModelController {

    private final EmbeddingTemplate embeddingTemplate;
    private final ObjectProvider<EmbeddingModelRegistry> registryProvider;

    /**
     * 多模型命名 Embedding 计算与维度探测
     *
     * @param request 向量化入参
     * @return 各模型计算的维度与向量前 5 维预览
     */
    @PostMapping("/models")
    public Result<AiRagMultiModelEmbedVo> embedWithModels(@Valid @RequestBody AiRagMultiModelEmbedRequest request) {
        String text = request.getText();
        List<String> targetModels = request.getModelNames();

        EmbeddingModelRegistry registry = registryProvider.getIfAvailable();
        if ((targetModels == null || targetModels.isEmpty()) && registry != null) {
            targetModels = new ArrayList<>(registry.listModels());
        }

        List<AiRagMultiModelEmbedVo.ModelEmbeddingItem> items = new ArrayList<>();

        if (targetModels != null && !targetModels.isEmpty()) {
            for (String modelName : targetModels) {
                Response<Embedding> resp = embeddingTemplate.embedWithModel(modelName, text);
                Embedding embedding = resp != null ? resp.content() : null;
                float[] vector = embedding != null ? embedding.vector() : new float[0];

                List<Float> preview = new ArrayList<>();
                for (int i = 0; i < Math.min(5, vector.length); i++) {
                    preview.add(vector[i]);
                }

                items.add(AiRagMultiModelEmbedVo.ModelEmbeddingItem.builder()
                        .modelName(modelName)
                        .dimension(vector.length)
                        .preview(preview)
                        .build());
            }
        } else {
            // 使用默认当前主模型
            Response<Embedding> resp = embeddingTemplate.embed(text);
            Embedding embedding = resp != null ? resp.content() : null;
            float[] vector = embedding != null ? embedding.vector() : new float[0];

            List<Float> preview = new ArrayList<>();
            for (int i = 0; i < Math.min(5, vector.length); i++) {
                preview.add(vector[i]);
            }

            items.add(AiRagMultiModelEmbedVo.ModelEmbeddingItem.builder()
                    .modelName("default")
                    .dimension(vector.length)
                    .preview(preview)
                    .build());
        }

        AiRagMultiModelEmbedVo vo = AiRagMultiModelEmbedVo.builder()
                .text(text)
                .totalModels(items.size())
                .models(items)
                .build();

        return Result.success(vo);
    }
}
