package io.github.atengk.boot.sample.core.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.core.dto.BatchAndCacheRequest;
import io.github.atengk.boot.sample.core.dto.SpelAndPrimitivesRequest;
import io.github.atengk.boot.sample.core.service.CoreDataProcessingScenarioService;
import io.github.atengk.boot.sample.core.vo.BatchAndCacheVO;
import io.github.atengk.boot.sample.core.vo.CategoryTreeVO;
import io.github.atengk.boot.sample.core.vo.SpelAndPrimitivesVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Core 数据结构、流式切片与通用算法原语控制器
 * 演示商品类目树构建与全景导航（TreeUtil）、大批量零拷贝分批切片（BatchSliceUtil）与有界租户配额缓存（ConcurrentLruCache）、
 * 安全受限只读 SpEL 表达式求值沙箱（SpelUtil）及通用算法原语（BeanUtil、LambdaUtil、DateTimeUtil、ConvertUtil、StringUtil、RandomUtil 与元组）
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Slf4j
@RestController
@RequestMapping("/sample/core/data")
@RequiredArgsConstructor
@Validated
public class CoreDataAndTreeController {

    private final CoreDataProcessingScenarioService dataProcessingService;

    /**
     * 1. 商品类目树构建与多维全景导航
     * 演示 TreeUtil O(N) 线性装配、递归剪枝、先序展平、面包屑寻路及终端叶子节点提取
     *
     * @param keyword        剪枝过滤关键词（可为空）
     * @param targetNodeName 面包屑导航目标节点名（可为空，默认取 "5G智能手机"）
     * @return 商品类目树与全景导航视图对象
     */
    @GetMapping("/category-tree")
    public Result<CategoryTreeVO> getCategoryTree(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String targetNodeName
    ) {
        log.info("接收商品类目树组装与导航请求: keyword={}, targetNodeName={}", keyword, targetNodeName);
        CategoryTreeVO vo = dataProcessingService.processCategoryTreeScenario(keyword, targetNodeName);
        return Result.success(vo);
    }

    /**
     * 2. 大批量切片流转与有界租户配额缓存演进
     * 演示 BatchSliceUtil 零拷贝切片、MoreCollectors 安全收集、ConcurrentLruCache 租户配额淘汰与 JsonUtil.deepClone 内存真明文
     *
     * @param request 切片与租户配额缓存请求载荷
     * @return 业务切片与缓存状态演进视图
     */
    @PostMapping("/batch-and-cache")
    public Result<BatchAndCacheVO> processBatchAndCache(@RequestBody @Valid BatchAndCacheRequest request) {
        log.info("接收大批量切片与租户缓存请求: orderCount={}, sliceSize={}, tenantId={}",
                request.getOrderCodes() != null ? request.getOrderCodes().size() : 0,
                request.getSliceSize(),
                request.getTenantId());
        BatchAndCacheVO vo = dataProcessingService.processBatchAndCacheScenario(request);
        return Result.success(vo);
    }

    /**
     * 3. 安全受限 SpEL 表达式求值与高频通用算法原语
     * 演示 SpelUtil 只读沙箱防注入、BeanUtil.copyIgnoreNull 局部增量合并、LambdaUtil 字段推导、DateTimeUtil 工作日、ConvertUtil、StringUtil、RandomUtil 与元组
     *
     * @param request 表达式求值与算法原语请求载荷
     * @return 响应视图对象
     */
    @PostMapping("/spel-and-primitives")
    public Result<SpelAndPrimitivesVO> processSpelAndPrimitives(@RequestBody @Valid SpelAndPrimitivesRequest request) {
        log.info("接收 SpEL 与高频算法原语请求: expression={}, simulateMaliciousSpel={}, hasUser={}",
                request.getExpression(),
                request.isSimulateMaliciousSpel(),
                request.getUser() != null);
        SpelAndPrimitivesVO vo = dataProcessingService.processSpelAndPrimitivesScenario(request);
        return Result.success(vo);
    }
}
