package io.github.atengk.boot.sample.ai.controller;

import io.github.atengk.boot.ai.rag.ingest.IngestOptions;
import io.github.atengk.boot.ai.rag.ingest.IngestResult;
import io.github.atengk.boot.ai.template.RagIngestionTemplate;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.ai.dto.AiRagIngestPipelineRequest;
import io.github.atengk.boot.sample.ai.vo.AiRagIngestPipelineVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI RAG 端到端 ETL Ingestion 摄取管道与生命周期治理接口
 *
 * @author Ateng
 * @since 2026-11-05
 */
@RestController
@RequestMapping("/sample/ai/rag/ingest")
@RequiredArgsConstructor
public class AiRagPipelineController {

    private final RagIngestionTemplate ingestionTemplate;

    /**
     * 端到端文档上传、解析、元数据治理、切片与幂等入库流水线
     *
     * @param request 摄取流水线入参
     * @return 摄取执行结果
     */
    @PostMapping("/pipeline")
    public Result<AiRagIngestPipelineVo> ingestPipeline(@Valid @RequestBody AiRagIngestPipelineRequest request) {
        IngestOptions options = IngestOptions.builder()
                .docId(request.getDocId())
                .docName(request.getDocName())
                .policy(request.getPolicy())
                .tenantId(request.getTenantId())
                .customMetadata(request.getCustomMetadata())
                .build();

        IngestResult result = ingestionTemplate.ingest(request.getContent(), request.getDocName(), options);

        String message = result.isSkipped()
                ? "文档内容哈希未变，命中 SKIP 幂等策略跳过重复计算与切片入库"
                : "文档成功摄取入库并完成向量化计算，共持久化 " + result.getChunkCount() + " 个切片";

        AiRagIngestPipelineVo vo = AiRagIngestPipelineVo.builder()
                .docId(result.getDocId())
                .docName(result.getDocName())
                .docHash(result.getDocHash())
                .tenantId(result.getTenantId())
                .chunkCount(result.getChunkCount())
                .skipped(result.isSkipped())
                .executionDurationMs(result.getExecutionDuration() != null ? result.getExecutionDuration().toMillis() : 0)
                .message(message)
                .build();

        return Result.success(vo);
    }
}
