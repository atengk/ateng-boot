package io.github.atengk.boot.sample.milvus.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.milvus.model.MilvusHit;
import io.github.atengk.boot.milvus.model.MilvusSearchRequest;
import io.github.atengk.boot.milvus.template.MilvusTemplate;
import io.github.atengk.boot.sample.milvus.converter.DocumentConverter;
import io.github.atengk.boot.sample.milvus.dto.GroupBySearchDto;
import io.github.atengk.boot.sample.milvus.dto.RangeSearchDto;
import io.github.atengk.boot.sample.milvus.model.SampleDocumentEntity;
import io.github.atengk.boot.sample.milvus.vo.DocumentHitVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Milvus 高级特征检索控制器 (GroupBy 去重聚合检索与 Range Search 范围检索)
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/milvus/search")
@RequiredArgsConstructor
public class MilvusAdvancedSearchController {

    private final MilvusTemplate milvusTemplate;

    /**
     * 单向量 GroupBy 去重聚合检索 (按指定标量字段每个分组仅保留最匹配的 Top 1 结果)
     *
     * @param dto GroupBy 请求入参
     * @return 命中项列表 (按相似度排序)
     */
    @PostMapping("/group-by")
    public Result<List<DocumentHitVo>> searchWithGroupBy(@Valid @RequestBody GroupBySearchDto dto) {
        String targetCollection = StringUtils.hasText(dto.getCollectionName())
                ? dto.getCollectionName().trim()
                : "sample_document";

        MilvusSearchRequest<SampleDocumentEntity> request = MilvusSearchRequest.<SampleDocumentEntity>builder()
                .collectionName(targetCollection)
                .entityClass(SampleDocumentEntity.class)
                .vector(dto.getVector())
                .topK(dto.getTopK() != null && dto.getTopK() > 0 ? dto.getTopK() : 5)
                .groupByFieldName(dto.getGroupByField().trim())
                .expr(dto.getExpr())
                .build();

        log.info("执行 GroupBy 聚合检索: collection={}, groupByField={}, topK={}",
                targetCollection, dto.getGroupByField(), request.getTopK());

        List<MilvusHit<SampleDocumentEntity>> hits = milvusTemplate.search(request);
        return Result.success(DocumentConverter.toHitVoList(hits));
    }

    /**
     * 单向量范围过滤检索 (Range Search，根据相似度 radius/rangeFilter 圈定置信度区间)
     *
     * @param dto 范围检索入参
     * @return 命中项列表 (满足区间约束的结果)
     */
    @PostMapping("/range")
    public Result<List<DocumentHitVo>> searchWithRange(@Valid @RequestBody RangeSearchDto dto) {
        String targetCollection = StringUtils.hasText(dto.getCollectionName())
                ? dto.getCollectionName().trim()
                : "sample_document";

        MilvusSearchRequest<SampleDocumentEntity> request = MilvusSearchRequest.<SampleDocumentEntity>builder()
                .collectionName(targetCollection)
                .entityClass(SampleDocumentEntity.class)
                .vector(dto.getVector())
                .topK(dto.getTopK() != null && dto.getTopK() > 0 ? dto.getTopK() : 10)
                .radius(dto.getRadius())
                .rangeFilter(dto.getRangeFilter())
                .expr(dto.getExpr())
                .build();

        log.info("执行范围检索 (Range Search): collection={}, radius={}, rangeFilter={}, topK={}",
                targetCollection, dto.getRadius(), dto.getRangeFilter(), request.getTopK());

        List<MilvusHit<SampleDocumentEntity>> hits = milvusTemplate.search(request);
        return Result.success(DocumentConverter.toHitVoList(hits));
    }
}
