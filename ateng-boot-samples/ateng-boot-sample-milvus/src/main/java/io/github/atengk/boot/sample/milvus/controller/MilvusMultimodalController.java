package io.github.atengk.boot.sample.milvus.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.milvus.model.AnnSearch;
import io.github.atengk.boot.milvus.model.MilvusHit;
import io.github.atengk.boot.milvus.model.MilvusHybridSearchRequest;
import io.github.atengk.boot.milvus.model.MilvusSearchRequest;
import io.github.atengk.boot.milvus.template.MilvusTemplate;
import io.github.atengk.boot.sample.milvus.converter.MultimodalConverter;
import io.github.atengk.boot.sample.milvus.dto.MultimodalDenseSearchDto;
import io.github.atengk.boot.sample.milvus.dto.MultimodalHybridSearchDto;
import io.github.atengk.boot.sample.milvus.dto.MultimodalInsertDto;
import io.github.atengk.boot.sample.milvus.dto.MultimodalSparseSearchDto;
import io.github.atengk.boot.sample.milvus.model.SampleMultimodalEntity;
import io.github.atengk.boot.sample.milvus.vo.MultimodalHitVo;
import io.github.atengk.boot.sample.milvus.vo.MultimodalVo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;

/**
 * Milvus 多模态多特征向量控制器 (稠密向量 + 稀疏向量 + 倒排索引 + 动态列 + 多路混合召回)
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/milvus/multimodal")
@RequiredArgsConstructor
public class MilvusMultimodalController {

    private final MilvusTemplate milvusTemplate;

    /**
     * 一键初始化多模态集合、倒排索引与多向量索引
     *
     * @return 初始化结果描述
     */
    @PostMapping("/init")
    public Result<String> initCollection() {
        log.info("初始化多模态集合 sample_multimodal 及索引");
        milvusTemplate.createCollectionAndIndex(SampleMultimodalEntity.class);
        return Result.success("多模态集合与向量索引初始化成功并已就绪加载");
    }

    /**
     * 单篇多模态文档新增写入 (含稠密、稀疏与动态扩展属性)
     *
     * @param dto 多模态文档入参 DTO
     * @return 成功写入的文档 ID
     */
    @PostMapping("/insert")
    public Result<Long> insert(@Valid @RequestBody MultimodalInsertDto dto) {
        SampleMultimodalEntity entity = MultimodalConverter.toEntity(dto);
        log.info("写入多模态文档: id={}, title={}", entity.getId(), entity.getTitle());
        milvusTemplate.insertEntities(Collections.singletonList(entity));
        return Result.success(entity.getId());
    }

    /**
     * 主键点查多模态文档详情
     *
     * @param id 文档唯一主键 ID
     * @return 强类型多模态文档视图对象
     */
    @GetMapping("/{id}")
    public Result<MultimodalVo> getById(@PathVariable("id") @NotNull(message = "文档ID不能为空") Long id) {
        log.info("点查多模态文档: id={}", id);
        SampleMultimodalEntity entity = milvusTemplate.getById(SampleMultimodalEntity.class, id);
        return Result.success(MultimodalConverter.toVo(entity));
    }

    /**
     * 稠密语义向量 ANN 相似度检索
     *
     * @param dto 稠密向量检索入参
     * @return 命中项列表 (按余弦相似度降序)
     */
    @PostMapping("/search/dense")
    public Result<List<MultimodalHitVo>> searchDense(@Valid @RequestBody MultimodalDenseSearchDto dto) {
        MilvusSearchRequest<SampleMultimodalEntity> request = MilvusSearchRequest.<SampleMultimodalEntity>builder()
                .collectionName("sample_multimodal")
                .entityClass(SampleMultimodalEntity.class)
                .annsField("dense_vector")
                .vector(dto.getVector())
                .topK(dto.getTopK() != null ? dto.getTopK() : 5)
                .expr(dto.getExpr())
                .build();

        log.info("执行多模态稠密向量检索: topK={}, expr={}", request.getTopK(), dto.getExpr());
        List<MilvusHit<SampleMultimodalEntity>> hits = milvusTemplate.search(request);
        return Result.success(MultimodalConverter.toHitVoList(hits));
    }

    /**
     * 稀疏关键词向量 ANN 相似度检索 (BM25 / SPLADE)
     *
     * @param dto 稀疏向量检索入参
     * @return 命中项列表 (按内积相似度降序)
     */
    @PostMapping("/search/sparse")
    public Result<List<MultimodalHitVo>> searchSparse(@Valid @RequestBody MultimodalSparseSearchDto dto) {
        MilvusSearchRequest<SampleMultimodalEntity> request = MilvusSearchRequest.<SampleMultimodalEntity>builder()
                .collectionName("sample_multimodal")
                .entityClass(SampleMultimodalEntity.class)
                .annsField("sparse_vector")
                .sparseVector(dto.getSparseVector())
                .topK(dto.getTopK() != null ? dto.getTopK() : 5)
                .expr(dto.getExpr())
                .build();

        log.info("执行多模态稀疏向量检索: topK={}, expr={}", request.getTopK(), dto.getExpr());
        List<MilvusHit<SampleMultimodalEntity>> hits = milvusTemplate.search(request);
        return Result.success(MultimodalConverter.toHitVoList(hits));
    }

    /**
     * 多模态稠密与稀疏多路混合检索与重排融合 (RRF)
     *
     * @param dto 混合检索入参
     * @return 融合重排后的命中项列表
     */
    @PostMapping("/search/hybrid")
    public Result<List<MultimodalHitVo>> searchHybrid(@Valid @RequestBody MultimodalHybridSearchDto dto) {
        int topK = dto.getTopK() != null ? dto.getTopK() : 5;
        MilvusHybridSearchRequest<SampleMultimodalEntity> request = MilvusHybridSearchRequest.of(SampleMultimodalEntity.class)
                .collectionName("sample_multimodal")
                .topK(topK)
                .addSearch(AnnSearch.of("dense_vector", dto.getDenseVector(), topK, dto.getFilterExpr()))
                .addSearch(AnnSearch.ofSparse("sparse_vector", dto.getSparseVector(), topK, dto.getFilterExpr()))
                .rrf(60);

        log.info("执行多模态稠密+稀疏多路混合检索: topK={}, filterExpr={}", topK, dto.getFilterExpr());
        List<MilvusHit<SampleMultimodalEntity>> hits = milvusTemplate.hybridSearch(request);
        return Result.success(MultimodalConverter.toHitVoList(hits));
    }
}
