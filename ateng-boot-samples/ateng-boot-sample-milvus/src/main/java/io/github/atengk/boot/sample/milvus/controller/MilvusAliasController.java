package io.github.atengk.boot.sample.milvus.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.milvus.template.MilvusTemplate;
import io.github.atengk.boot.sample.milvus.dto.AliasAlterDto;
import io.github.atengk.boot.sample.milvus.dto.AliasCreateDto;
import io.github.atengk.boot.sample.milvus.vo.AliasDetailVo;
import io.milvus.v2.service.utility.response.DescribeAliasResp;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Milvus 集合别名 (Collection Alias) 与蓝绿无感发布控制器
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/milvus/alias")
@RequiredArgsConstructor
public class MilvusAliasController {

    private final MilvusTemplate milvusTemplate;

    /**
     * 为指定集合创建逻辑别名
     *
     * @param dto 创建别名请求入参
     * @return 统一响应契约
     */
    @PostMapping("/create")
    public Result<Boolean> createAlias(@Valid @RequestBody AliasCreateDto dto) {
        log.info("创建集合别名: collection={}, alias={}", dto.getCollectionName(), dto.getAlias());
        milvusTemplate.createAlias(dto.getCollectionName(), dto.getAlias());
        return Result.success(true);
    }

    /**
     * 平滑切换别名指向的目标集合 (蓝绿发布无感切流，具备前置内存加载状态校验)
     *
     * @param dto 变更别名请求入参
     * @return 统一响应契约
     */
    @PostMapping("/alter")
    public Result<Boolean> alterAlias(@Valid @RequestBody AliasAlterDto dto) {
        log.info("切换集合别名: collection={}, alias={}, checkLoaded={}",
                dto.getCollectionName(), dto.getAlias(), dto.isCheckLoaded());
        milvusTemplate.alterAlias(dto.getCollectionName(), dto.getAlias(), dto.isCheckLoaded());
        return Result.success(true);
    }

    /**
     * 删除指定的集合别名
     *
     * @param alias 待删除的别名
     * @return 统一响应契约
     */
    @DeleteMapping("/drop")
    public Result<Boolean> dropAlias(@RequestParam("alias") @NotBlank(message = "别名不能为空") String alias) {
        log.info("删除集合别名: alias={}", alias);
        milvusTemplate.dropAlias(alias);
        return Result.success(true);
    }

    /**
     * 查询指定集合绑定的所有别名列表
     *
     * @param collectionName 集合名称
     * @return 别名列表
     */
    @GetMapping("/list")
    public Result<List<String>> listAliases(@RequestParam("collectionName") @NotBlank(message = "集合名称不能为空") String collectionName) {
        List<String> aliases = milvusTemplate.listAliases(collectionName);
        return Result.success(aliases);
    }

    /**
     * 查询别名详细元数据信息 (绑定的目标物理集合等)
     *
     * @param alias 逻辑别名
     * @return 别名详情
     */
    @GetMapping("/detail")
    public Result<AliasDetailVo> describeAlias(@RequestParam("alias") @NotBlank(message = "别名不能为空") String alias) {
        DescribeAliasResp resp = milvusTemplate.describeAlias(alias);
        return Result.success(AliasDetailVo.builder()
                .alias(resp.getAlias())
                .collectionName(resp.getCollectionName())
                .build());
    }
}
