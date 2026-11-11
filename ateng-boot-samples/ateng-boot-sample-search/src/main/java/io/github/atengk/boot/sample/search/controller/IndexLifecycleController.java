package io.github.atengk.boot.sample.search.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.search.dto.AliasSwapRequest;
import io.github.atengk.boot.sample.search.dto.IndexCreateRequest;
import io.github.atengk.boot.sample.search.dto.IndexTemplateRequest;
import io.github.atengk.boot.sample.search.dto.ReindexRequest;
import io.github.atengk.boot.sample.search.scenario.ZeroDowntimeReindexScenario;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 生产索引 DDL 结构、时序模板与零停机平滑热升级控制器
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/search/index")
@RequiredArgsConstructor
public class IndexLifecycleController {

    private final ZeroDowntimeReindexScenario zeroDowntimeScenario;

    /**
     * 创建目标索引并定义 Mapping 结构
     */
    @PostMapping("/create")
    public Result<Boolean> createIndex(@RequestBody @Valid IndexCreateRequest request) {
        boolean created = zeroDowntimeScenario.initIndexWithMapping(request.getIndexName(), request.getMappingJson());
        return Result.success(created);
    }

    /**
     * 创建或更新时序/组件索引模板 (Index Template)
     */
    @PostMapping("/template/apply")
    public Result<Boolean> applyIndexTemplate(@RequestBody @Valid IndexTemplateRequest request) {
        boolean applied = zeroDowntimeScenario.applyIndexTemplate(request.getTemplateName(), request.getTemplateJson());
        return Result.success(applied);
    }

    /**
     * 删除指定的索引模板 (Index Template)
     */
    @DeleteMapping("/template/delete")
    public Result<Boolean> deleteIndexTemplate(@RequestParam String templateName) {
        boolean deleted = zeroDowntimeScenario.removeIndexTemplate(templateName);
        return Result.success(deleted);
    }

    /**
     * 服务端平滑数据重索引 (全量复制或带时间范围增量重索引)
     */
    @PostMapping("/reindex")
    public Result<Boolean> reindex(@RequestBody @Valid ReindexRequest request) {
        boolean success;
        if (request.getMinTime() != null) {
            success = zeroDowntimeScenario.reindexIncremental(request.getSourceIndex(), request.getTargetIndex(), request.getMinTime());
        } else {
            success = zeroDowntimeScenario.reindexAll(request.getSourceIndex(), request.getTargetIndex());
        }
        return Result.success(success);
    }

    /**
     * 显式物理刷新索引（触发 Lucene 段物理合并与刷新，保障写后即读确定性）
     */
    @PostMapping("/refresh")
    public Result<Boolean> refreshIndex(@RequestParam String indexName) {
        boolean refreshed = zeroDowntimeScenario.refreshIndex(indexName);
        return Result.success(refreshed);
    }

    /**
     * 生产原子别名热切换 (swapAlias，单次底层请求原子执行 remove + add，实现零停机切源)
     */
    @PostMapping("/swap-alias")
    public Result<Boolean> swapAlias(@RequestBody @Valid AliasSwapRequest request) {
        boolean swapped = zeroDowntimeScenario.switchTrafficAlias(request.getAliasName(), request.getOldIndex(), request.getNewIndex());
        return Result.success(swapped);
    }
}
