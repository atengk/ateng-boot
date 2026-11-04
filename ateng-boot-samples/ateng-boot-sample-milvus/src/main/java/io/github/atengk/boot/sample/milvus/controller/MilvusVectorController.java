package io.github.atengk.boot.sample.milvus.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.milvus.model.MilvusHit;
import io.github.atengk.boot.milvus.template.MilvusTemplate;
import io.github.atengk.boot.sample.milvus.converter.DocumentConverter;
import io.github.atengk.boot.sample.milvus.dto.DocumentBatchInsertDto;
import io.github.atengk.boot.sample.milvus.dto.DocumentInsertDto;
import io.github.atengk.boot.sample.milvus.dto.VectorSearchDto;
import io.github.atengk.boot.sample.milvus.model.SampleDocumentEntity;
import io.github.atengk.boot.sample.milvus.vo.DocumentHitVo;
import io.github.atengk.boot.sample.milvus.vo.DocumentVo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Milvus 单向量 CRUD、主键点查、标量过滤与 ANN 相似度检索控制器
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/milvus/vector")
@RequiredArgsConstructor
public class MilvusVectorController {

    private final MilvusTemplate milvusTemplate;

    /**
     * 单文档向量新增写入
     *
     * @param request 插入请求 DTO
     * @return 成功写入的文档 ID
     */
    @PostMapping("/insert")
    public Result<Long> insert(@Valid @RequestBody DocumentInsertDto request) {
        SampleDocumentEntity entity = DocumentConverter.toEntity(request);
        milvusTemplate.insertEntities(Collections.singletonList(entity));
        return Result.success(entity.getId());
    }

    /**
     * 单文档覆盖写入 (主键存在则更新，不存在则插入)
     *
     * @param request 写入请求 DTO
     * @return 覆盖写入的文档 ID
     */
    @PostMapping("/upsert")
    public Result<Long> upsert(@Valid @RequestBody DocumentInsertDto request) {
        SampleDocumentEntity entity = DocumentConverter.toEntity(request);
        milvusTemplate.upsertEntities(Collections.singletonList(entity));
        return Result.success(entity.getId());
    }

    /**
     * 批量切片数据摄取 (自动分批切片与指数退避重试，防 OOM 与 gRPC 帧超限)
     *
     * @param request 批量插入请求 DTO
     * @return 写入文档记录条数
     */
    @PostMapping("/batch-insert")
    public Result<Integer> batchInsert(@Valid @RequestBody DocumentBatchInsertDto request) {
        List<SampleDocumentEntity> entities = request.getDocuments().stream()
                .map(DocumentConverter::toEntity)
                .collect(Collectors.toList());
        milvusTemplate.insertBatchEntities(entities);
        return Result.success(entities.size());
    }

    /**
     * 按主键 ID 查询文档详情
     *
     * @param id 文档主键 ID
     * @return 统一响应契约包装的文档视图 VO
     */
    @GetMapping("/get/{id}")
    public Result<DocumentVo> getById(@PathVariable("id") @NotNull(message = "文档ID不能为空") Long id) {
        SampleDocumentEntity entity = milvusTemplate.getById(SampleDocumentEntity.class, id);
        return Result.success(DocumentConverter.toVo(entity));
    }

    /**
     * 按主键 ID 删除文档
     *
     * @param id 文档主键 ID
     * @return 删除成功标识
     */
    @DeleteMapping("/delete/{id}")
    public Result<Boolean> deleteById(@PathVariable("id") @NotNull(message = "文档ID不能为空") Long id) {
        milvusTemplate.deleteById(SampleDocumentEntity.class, id);
        return Result.success(true);
    }

    /**
     * 单向量 ANN 相似度检索 (支持标量表达式组合过滤)
     *
     * @param request 检索请求 DTO
     * @return 检索命中结果列表 VO
     */
    @PostMapping("/search")
    public Result<List<DocumentHitVo>> search(@Valid @RequestBody VectorSearchDto request) {
        List<MilvusHit<SampleDocumentEntity>> hits = milvusTemplate.search(
                SampleDocumentEntity.class,
                request.getVector(),
                request.getTopK(),
                request.getFilterExpr()
        );
        List<DocumentHitVo> voList = hits.stream()
                .map(DocumentConverter::toHitVo)
                .collect(Collectors.toList());
        return Result.success(voList);
    }

    /**
     * 纯标量表达式查询
     *
     * @param expr 标量过滤表达式 (如 "category == 'AI' and id > 0")
     * @return 查询结果文档列表 VO
     */
    @GetMapping("/query")
    public Result<List<DocumentVo>> query(
            @RequestParam(name = "expr", defaultValue = "id > 0") String expr) {
        List<SampleDocumentEntity> entities = milvusTemplate.query(SampleDocumentEntity.class, expr);
        List<DocumentVo> voList = entities.stream()
                .map(DocumentConverter::toVo)
                .collect(Collectors.toList());
        return Result.success(voList);
    }

    /**
     * 统计满足条件的文档数量
     *
     * @param expr 可选标量过滤表达式
     * @return 满足条件的文档总数
     */
    @GetMapping("/count")
    public Result<Long> count(
            @RequestParam(name = "expr", required = false) String expr) {
        long total = milvusTemplate.count(SampleDocumentEntity.class, expr);
        return Result.success(total);
    }
}
