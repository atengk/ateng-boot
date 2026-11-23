package io.github.atengk.boot.sample.milvus.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.milvus.model.MilvusHit;
import io.github.atengk.boot.milvus.model.MilvusSearchRequest;
import io.github.atengk.boot.milvus.template.MilvusTemplate;
import io.github.atengk.boot.milvus.util.MilvusExprHelper;
import io.github.atengk.boot.sample.milvus.converter.DocumentConverter;
import io.github.atengk.boot.sample.milvus.dto.ExprSearchDto;
import io.github.atengk.boot.sample.milvus.dto.GroupBySearchDto;
import io.github.atengk.boot.sample.milvus.dto.RangeSearchDto;
import io.github.atengk.boot.sample.milvus.dto.StreamQueryDto;
import io.github.atengk.boot.sample.milvus.model.SampleDocumentEntity;
import io.github.atengk.boot.sample.milvus.vo.DocumentHitVo;
import io.github.atengk.boot.sample.milvus.vo.DocumentVo;
import io.github.atengk.boot.sample.milvus.vo.ExprSearchVo;
import io.github.atengk.boot.sample.milvus.vo.StreamQueryVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
        String targetCollection = resolveCollectionName(dto.getCollectionName());

        MilvusSearchRequest<SampleDocumentEntity> request = MilvusSearchRequest.<SampleDocumentEntity>builder()
                .collectionName(targetCollection)
                .entityClass(SampleDocumentEntity.class)
                .vector(dto.getVector())
                .topK(dto.getTopK() != null ? dto.getTopK() : 5)
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
        String targetCollection = resolveCollectionName(dto.getCollectionName());

        MilvusSearchRequest<SampleDocumentEntity> request = MilvusSearchRequest.<SampleDocumentEntity>builder()
                .collectionName(targetCollection)
                .entityClass(SampleDocumentEntity.class)
                .vector(dto.getVector())
                .topK(dto.getTopK() != null ? dto.getTopK() : 10)
                .radius(dto.getRadius())
                .rangeFilter(dto.getRangeFilter())
                .expr(dto.getExpr())
                .build();

        log.info("执行范围检索 (Range Search): collection={}, radius={}, rangeFilter={}, topK={}",
                targetCollection, dto.getRadius(), dto.getRangeFilter(), request.getTopK());

        List<MilvusHit<SampleDocumentEntity>> hits = milvusTemplate.search(request);
        return Result.success(DocumentConverter.toHitVoList(hits));
    }

    /**
     * 基于 MilvusExprHelper 安全构造复合标量与 TEXT_MATCH 全文过滤表达式检索
     *
     * @param dto 复合表达式检索入参 DTO
     * @return 编译后的表达式与命中结果视图 VO
     */
    @PostMapping("/expr")
    public Result<ExprSearchVo> searchWithExprHelper(@Valid @RequestBody ExprSearchDto dto) {
        String targetCollection = resolveCollectionName(dto.getCollectionName());

        MilvusExprHelper.Builder exprBuilder = MilvusExprHelper.builder();
        if (StringUtils.hasText(dto.getCategory())) {
            exprBuilder.eq("category", dto.getCategory().trim());
        }
        if (StringUtils.hasText(dto.getTitlePrefix())) {
            exprBuilder.like("title", dto.getTitlePrefix().trim());
        }
        if (dto.getMinId() != null) {
            exprBuilder.gte("id", dto.getMinId());
        }
        if (StringUtils.hasText(dto.getTextMatchQuery())) {
            exprBuilder.textMatch("content", dto.getTextMatchQuery().trim());
        }
        String compiledExpr = exprBuilder.build();
        BusinessAssert.hasText(compiledExpr, "至少需要提供一个有效的过滤条件");

        MilvusSearchRequest<SampleDocumentEntity> request = MilvusSearchRequest.<SampleDocumentEntity>builder()
                .collectionName(targetCollection)
                .entityClass(SampleDocumentEntity.class)
                .vector(dto.getVector())
                .topK(dto.getTopK() != null ? dto.getTopK() : 5)
                .expr(compiledExpr)
                .build();

        log.info("执行 MilvusExprHelper 复合过滤检索: collection={}, expr={}, topK={}",
                targetCollection, compiledExpr, request.getTopK());
        List<MilvusHit<SampleDocumentEntity>> hits = milvusTemplate.search(request);
        return Result.success(ExprSearchVo.builder()
                .compiledExpr(compiledExpr)
                .hits(DocumentConverter.toHitVoList(hits))
                .build());
    }

    /**
     * 基于 QueryIterator 执行大数据游标流式深度遍历 (突破 16384 offset 限制)
     *
     * @param dto 流式遍历请求 DTO
     * @return 遍历批次统计与文档视图列表 VO
     */
    @PostMapping("/stream")
    public Result<StreamQueryVo> streamingQuery(@Valid @RequestBody StreamQueryDto dto) {
        MilvusExprHelper.Builder exprBuilder = MilvusExprHelper.builder();
        if (StringUtils.hasText(dto.getCategory())) {
            exprBuilder.eq("category", dto.getCategory().trim());
        }
        if (dto.getMinId() != null) {
            exprBuilder.gte("id", dto.getMinId());
        }
        String filterExpr = exprBuilder.build();
        if (!StringUtils.hasText(filterExpr)) {
            filterExpr = "id > 0";
        }

        int batchSize = dto.getBatchSize() != null ? dto.getBatchSize() : 500;
        AtomicInteger batchCount = new AtomicInteger(0);
        List<DocumentVo> items = new ArrayList<>();

        milvusTemplate.streamingQueryEntities(SampleDocumentEntity.class, filterExpr, batchSize, batch -> {
            batchCount.incrementAndGet();
            batch.stream().map(DocumentConverter::toVo).forEach(items::add);
        });

        return Result.success(StreamQueryVo.builder()
                .filterExpr(filterExpr)
                .batchCount(batchCount.get())
                .totalCount(items.size())
                .items(items)
                .build());
    }

    private String resolveCollectionName(String collectionName) {
        return StringUtils.hasText(collectionName) ? collectionName.trim() : "sample_document";
    }
}
