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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
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
        if (StringUtils.hasText(effectiveTenantId)) {
            TenantContextHolder.setTenantId(effectiveTenantId);
        }
        try {
            SampleDocumentEntity entity = DocumentConverter.toEntity(request);
            if (StringUtils.hasText(effectiveTenantId)) {
                entity.setTenantId(effectiveTenantId);
            }
            milvusTemplate.insertEntities(Collections.singletonList(entity));
            return Result.success(entity.getId());
        } finally {
            TenantContextHolder.clear();
        }
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
        TenantContextHolder.setTenantId(tenantId.trim());
        try {
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
        } finally {
            TenantContextHolder.clear();
        }
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
        TenantContextHolder.setTenantId(tenantId.trim());
        try {
            long total = milvusTemplate.count(SampleDocumentEntity.class, expr);
            return Result.success(total);
        } finally {
            TenantContextHolder.clear();
        }
    }
}
