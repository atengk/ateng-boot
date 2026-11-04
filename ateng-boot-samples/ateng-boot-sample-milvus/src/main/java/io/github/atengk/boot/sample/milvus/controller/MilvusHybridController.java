package io.github.atengk.boot.sample.milvus.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.milvus.model.AnnSearch;
import io.github.atengk.boot.milvus.model.MilvusHit;
import io.github.atengk.boot.milvus.model.MilvusHybridSearchRequest;
import io.github.atengk.boot.milvus.template.MilvusTemplate;
import io.github.atengk.boot.sample.milvus.converter.DocumentConverter;
import io.github.atengk.boot.sample.milvus.dto.HybridSearchDto;
import io.github.atengk.boot.sample.milvus.model.SampleDocumentEntity;
import io.github.atengk.boot.sample.milvus.vo.DocumentHitVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Milvus 多向量多路召回与混合检索融合重排控制器
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/milvus/hybrid")
@RequiredArgsConstructor
public class MilvusHybridController {

    private final MilvusTemplate milvusTemplate;

    /**
     * 多向量多路混合检索与重排融合 (支持 RRF 与加权融合)
     *
     * @param request 混合检索请求 DTO
     * @return 最终融合重排后的命中结果列表 VO
     */
    @PostMapping("/search")
    public Result<List<DocumentHitVo>> hybridSearch(@Valid @RequestBody HybridSearchDto request) {
        MilvusHybridSearchRequest<SampleDocumentEntity> hybridReq = MilvusHybridSearchRequest.of(SampleDocumentEntity.class)
                .topK(request.getTopK())
                .addSearch(AnnSearch.of("vector", request.getVector(), request.getTopK(), request.getFilterExpr()));

        if (request.getSecondaryVector() != null && !request.getSecondaryVector().isEmpty()) {
            hybridReq.addSearch(AnnSearch.of("vector", request.getSecondaryVector(), request.getTopK()));
        }

        if ("WEIGHTED".equalsIgnoreCase(request.getRankerType()) && request.getWeights() != null && !request.getWeights().isEmpty()) {
            hybridReq.weighted(request.getWeights());
        } else {
            hybridReq.rrf(request.getRrfK() != null ? request.getRrfK() : 60);
        }

        List<MilvusHit<SampleDocumentEntity>> hits = milvusTemplate.hybridSearch(hybridReq);
        List<DocumentHitVo> voList = hits.stream()
                .map(DocumentConverter::toHitVo)
                .collect(Collectors.toList());
        return Result.success(voList);
    }
}
