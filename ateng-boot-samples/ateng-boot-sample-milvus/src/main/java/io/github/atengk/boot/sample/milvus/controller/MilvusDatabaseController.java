package io.github.atengk.boot.sample.milvus.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.milvus.context.MilvusContextHolder;
import io.github.atengk.boot.milvus.model.MilvusHit;
import io.github.atengk.boot.milvus.template.MilvusTemplate;
import io.github.atengk.boot.sample.milvus.converter.DocumentConverter;
import io.github.atengk.boot.sample.milvus.dto.DatabaseCreateDto;
import io.github.atengk.boot.sample.milvus.dto.DatabaseScopedSearchDto;
import io.github.atengk.boot.sample.milvus.model.SampleDocumentEntity;
import io.github.atengk.boot.sample.milvus.vo.DocumentHitVo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Milvus 多逻辑数据库生命周期运维与作用域动态切库检索控制器
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/milvus/database")
@RequiredArgsConstructor
public class MilvusDatabaseController {

    private final MilvusTemplate milvusTemplate;

    /**
     * 创建逻辑数据库
     *
     * @param dto 创建请求 DTO
     * @return 创建成功标识
     */
    @PostMapping("/create")
    public Result<Boolean> createDatabase(@Valid @RequestBody DatabaseCreateDto dto) {
        log.info("创建 Milvus 逻辑数据库: {}", dto.getDatabaseName());
        milvusTemplate.createDatabase(dto.getDatabaseName().trim());
        return Result.success(true);
    }

    /**
     * 判断指定逻辑数据库是否存在
     *
     * @param databaseName 数据库名称
     * @return 是否存在
     */
    @GetMapping("/has")
    public Result<Boolean> hasDatabase(
            @RequestParam("databaseName") @NotBlank(message = "数据库名称不能为空") String databaseName) {
        return Result.success(milvusTemplate.hasDatabase(databaseName));
    }

    /**
     * 查询所有逻辑数据库名称列表
     *
     * @return 数据库名称列表
     */
    @GetMapping("/list")
    public Result<List<String>> listDatabases() {
        return Result.success(milvusTemplate.listDatabases());
    }

    /**
     * 删除指定逻辑数据库
     *
     * @param databaseName 数据库名称
     * @return 删除成功标识
     */
    @DeleteMapping("/drop")
    public Result<Boolean> dropDatabase(
            @RequestParam("databaseName") @NotBlank(message = "数据库名称不能为空") String databaseName) {
        log.info("删除 Milvus 逻辑数据库: {}", databaseName);
        milvusTemplate.dropDatabase(databaseName.trim());
        return Result.success(true);
    }

    /**
     * 使用 AutoCloseable 作用域在目标逻辑数据库内执行向量检索并在退出后自动恢复上下文
     *
     * @param dto 作用域切库检索请求 DTO
     * @return 检索命中视图列表
     */
    @PostMapping("/search")
    public Result<List<DocumentHitVo>> scopedSearch(@Valid @RequestBody DatabaseScopedSearchDto dto) {
        try (MilvusContextHolder.DatabaseScope ignored = MilvusContextHolder.useDatabase(dto.getDatabaseName())) {
            List<MilvusHit<SampleDocumentEntity>> hits = milvusTemplate.search(
                    SampleDocumentEntity.class, dto.getVector(), dto.getTopK(), dto.getFilterExpr());
            return Result.success(DocumentConverter.toHitVoList(hits));
        }
    }
}
