package io.github.atengk.boot.sample.ai.controller;

import io.github.atengk.boot.ai.template.RagIngestionTemplate;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.ai.dto.AiRagDocumentDeleteRequest;
import io.github.atengk.boot.sample.ai.vo.AiRagDocumentDeleteVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI RAG 知识库文档级联清理接口
 *
 * @author Ateng
 * @since 2026-11-05
 */
@RestController
@RequestMapping("/sample/ai/rag/document")
@RequiredArgsConstructor
public class AiRagDocumentController {

    private final RagIngestionTemplate ingestionTemplate;

    /**
     * 根据文档业务标识 docId 级联物理清理向量库中所有归属分块切片
     *
     * @param request 文档删除请求入参
     * @return 删除执行结果
     */
    @PostMapping("/delete")
    public Result<AiRagDocumentDeleteVo> deleteDocument(@Valid @RequestBody AiRagDocumentDeleteRequest request) {
        String docId = request.getDocId();
        ingestionTemplate.deleteDocument(docId);

        AiRagDocumentDeleteVo vo = AiRagDocumentDeleteVo.builder()
                .docId(docId)
                .deleted(true)
                .message("成功级联清理知识库文档所有关联切片与向量索引 [docId=" + docId + "]")
                .build();

        return Result.success(vo);
    }
}
