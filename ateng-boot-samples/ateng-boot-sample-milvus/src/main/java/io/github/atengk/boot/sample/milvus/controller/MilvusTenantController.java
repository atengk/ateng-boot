package io.github.atengk.boot.sample.milvus.controller;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.milvus.model.MilvusHit;
import io.github.atengk.boot.milvus.template.MilvusTemplate;
import io.github.atengk.boot.sample.milvus.converter.DocumentConverter;
import io.github.atengk.boot.sample.milvus.dto.DocumentInsertDto;
import io.github.atengk.boot.sample.milvus.dto.VectorSearchDto;
import io.github.atengk.boot.sample.milvus.model.SampleDocumentEntity;
import io.github.atengk.boot.sample.milvus.vo.DocumentHitVo;
import io.github.atengk.boot.sample.milvus.vo.DocumentVo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Milvus SaaS 多租户隔离读写与安全织入控制器
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/milvus/tenant")
@RequiredArgsConstructor
public class MilvusTenantController {

    private final MilvusTemplate milvusTemplate;

    /**
     * 租户受限写入 (自动补全租户字段)
     *
     * @param request        文档插入 DTO
     * @param headerTenantId HTTP 请求头中的租户标识
     * @return 成功写入的文档 ID
     */
    @PostMapping("/insert")
    public Result<Long> insertWithTenant(
            @Valid @RequestBody DocumentInsertDto request,
            @RequestHeader(value = "X-Tenant-Id", required = false) String headerTenantId) {
        String effectiveTenantId = StringUtils.hasText(headerTenantId)
                ? headerTenantId.trim()
                : request.getTenantId();
        SampleDocumentEntity entity = DocumentConverter.toEntity(request);
        if (!StringUtils.hasText(effectiveTenantId)) {
            milvusTemplate.insertEntities(Collections.singletonList(entity));
            return Result.success(entity.getId());
        }
        return Result.success(executeWithTenant(effectiveTenantId, () -> {
            milvusTemplate.insertEntities(Collections.singletonList(entity));
            return entity.getId();
        }));
    }

    /**
     * 租户上下文受限向量相似度检索 (自动织入租户隔离过滤)
     *
     * @param request  向量检索请求 DTO
     * @param tenantId HTTP 请求头指定的租户标识
     * @return 租户安全隔离后的检索命中结果列表 VO
     */
    @PostMapping("/search")
    public Result<List<DocumentHitVo>> searchWithTenant(
            @Valid @RequestBody VectorSearchDto request,
            @RequestHeader("X-Tenant-Id") @NotBlank(message = "租户标识 X-Tenant-Id 不能为空") String tenantId) {
        List<DocumentHitVo> voList = executeWithTenant(tenantId, () -> {
            List<MilvusHit<SampleDocumentEntity>> hits = milvusTemplate.search(
                    SampleDocumentEntity.class,
                    request.getVector(),
                    request.getTopK(),
                    request.getFilterExpr()
            );
            return hits.stream()
                    .map(DocumentConverter::toHitVo)
                    .collect(Collectors.toList());
        });
        return Result.success(voList);
    }

    /**
     * 租户上下文受限实体数量统计
     *
     * @param tenantId HTTP 请求头指定的租户标识
     * @param expr     可选附加标量过滤表达式
     * @return 该租户下满足条件的文档总数
     */
    @GetMapping("/count")
    public Result<Long> countWithTenant(
            @RequestHeader("X-Tenant-Id") @NotBlank(message = "租户标识 X-Tenant-Id 不能为空") String tenantId,
            @RequestParam(name = "expr", required = false) String expr) {
        long total = executeWithTenant(tenantId, () -> milvusTemplate.count(SampleDocumentEntity.class, expr));
        return Result.success(total);
    }

    /**
     * 租户上下文受限主键点查 (自动防御跨租户越权读取)
     *
     * @param id       文档主键 ID
     * @param tenantId HTTP 请求头指定的租户标识
     * @return 属于当前租户的文档视图 VO，跨租户访问时返回 null
     */
    @GetMapping("/get/{id}")
    public Result<DocumentVo> getByIdWithTenant(
            @PathVariable("id") @NotNull(message = "文档ID不能为空") Long id,
            @RequestHeader("X-Tenant-Id") @NotBlank(message = "租户标识 X-Tenant-Id 不能为空") String tenantId) {
        SampleDocumentEntity entity = executeWithTenant(tenantId,
                () -> milvusTemplate.getById(SampleDocumentEntity.class, id));
        return Result.success(DocumentConverter.toVo(entity));
    }

    /**
     * 租户上下文受限安全删除 (自动织入租户隔离表达式，杜绝越权删数)
     *
     * @param id       文档主键 ID
     * @param tenantId HTTP 请求头指定的租户标识
     * @return 删除成功标识
     */
    @DeleteMapping("/delete/{id}")
    public Result<Boolean> deleteByIdWithTenant(
            @PathVariable("id") @NotNull(message = "文档ID不能为空") Long id,
            @RequestHeader("X-Tenant-Id") @NotBlank(message = "租户标识 X-Tenant-Id 不能为空") String tenantId) {
        executeWithTenant(tenantId, () -> {
            milvusTemplate.deleteById(SampleDocumentEntity.class, id);
            return Boolean.TRUE;
        });
        return Result.success(true);
    }

    private <T> T executeWithTenant(String tenantId, Supplier<T> action) {
        TenantContextHolder.setTenantId(tenantId.trim());
        try {
            return action.get();
        } finally {
            TenantContextHolder.clear();
        }
    }
}
