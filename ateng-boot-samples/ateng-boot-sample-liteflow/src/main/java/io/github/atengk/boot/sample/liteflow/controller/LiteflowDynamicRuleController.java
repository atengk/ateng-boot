package io.github.atengk.boot.sample.liteflow.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.liteflow.core.LiteflowElValidationResult;
import io.github.atengk.boot.liteflow.core.LiteflowTemplate;
import io.github.atengk.boot.liteflow.rule.event.LiteflowRuleRefreshEvent;
import io.github.atengk.boot.liteflow.trace.LiteflowTraceResult;
import io.github.atengk.boot.sample.liteflow.context.DynamicFlowContext;
import io.github.atengk.boot.sample.liteflow.model.DynamicChainReloadRequest;
import io.github.atengk.boot.sample.liteflow.model.DynamicElExecuteRequest;
import io.github.atengk.boot.sample.liteflow.model.DynamicElExecuteResponse;
import io.github.atengk.boot.sample.liteflow.model.DynamicElValidateRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Set;

/**
 * 动态规则热重载与在线即时 EL 执行接口控制器
 * 演示：规则链动态热重载 (reloadChain)、即时在线 EL 表达式执行 (executeElWithTrace)、规则链探测 (containChain) 与事件总线热更 (refresh-event)
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/liteflow/dynamic")
@RequiredArgsConstructor
public class LiteflowDynamicRuleController {

    private final LiteflowTemplate liteflowTemplate;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 在线热重载或动态注册一条规则链
     *
     * @param request 重载请求
     * @return 是否成功
     */
    @PostMapping("/reload-chain")
    public Result<Boolean> reloadChain(@Valid @RequestBody DynamicChainReloadRequest request) {
        log.info("【动态规则】动态更新规则链：chainId={}, el={}", request.getChainId(), request.getEl());
        liteflowTemplate.reloadChain(request.getChainId(), request.getEl());
        return Result.success(true);
    }

    /**
     * 在线即时编译并执行 EL 表达式（无需提前预定义 chainId）
     *
     * @param request 即时执行请求
     * @return 执行产出与轨迹
     */
    @PostMapping("/execute-el")
    public Result<DynamicElExecuteResponse> executeEl(@Valid @RequestBody DynamicElExecuteRequest request) {
        log.info("【动态规则】即时执行 EL 表达式：el={}, payload={}", request.getEl(), request.getPayload());

        LiteflowTraceResult<DynamicFlowContext> traceResult = liteflowTemplate.executeElWithTrace(
                request.getEl(), request.getPayload(), DynamicFlowContext.class);

        DynamicFlowContext context = traceResult.getContext();
        String traceSummary = traceResult.getTrace() != null
                ? traceResult.getTrace().formatSummary()
                : "N/A";
        Long duration = traceResult.getTrace() != null ? traceResult.getTrace().getTotalTimeSpentMs() : null;

        DynamicElExecuteResponse response = DynamicElExecuteResponse.builder()
                .success(true)
                .executeTimeMs(duration)
                .outputData(context != null ? new HashMap<>(context.getAttributes()) : new HashMap<>())
                .traceSummary(traceSummary)
                .build();

        return Result.success(response);
    }

    /**
     * 在线预校验 EL 规则表达式语法有效性
     *
     * @param request 校验请求
     * @return 结构化校验结果
     */
    @PostMapping("/validate-el")
    public Result<LiteflowElValidationResult> validateEl(@Valid @RequestBody DynamicElValidateRequest request) {
        log.info("【动态规则】在线校验 EL 表达式语法：el={}", request.getEl());
        LiteflowElValidationResult result = liteflowTemplate.validateEl(request.getEl());
        return Result.success(result);
    }

    /**
     * 获取当前系统已编译加载的全部规则链 ID 集合
     *
     * @return 规则链 ID 集合
     */
    @GetMapping("/chains")
    public Result<Set<String>> getLoadedChains() {
        Set<String> chains = liteflowTemplate.getLoadedChainIds();
        log.info("【动态规则】获取已加载流程链列表，当前数量: {}", chains.size());
        return Result.success(chains);
    }

    /**
     * 探测当前运行环境中指定 chainId 是否已被加载就绪
     *
     * @param chainId 规则链标识
     * @return 是否存在
     */
    @GetMapping("/contain-chain")
    public Result<Boolean> containChain(@NotBlank(message = "规则链 chainId 不能为空") @RequestParam("chainId") String chainId) {
        boolean exists = liteflowTemplate.containChain(chainId);
        log.info("【动态规则】探测规则链就绪状态：chainId={}, exists={}", chainId, exists);
        return Result.success(exists);
    }

    /**
     * 通过 Spring 领域事件总线触发规则热更新
     *
     * @param chainId 规则链标识（可选，若不传则触发全量规则源刷新）
     * @param el      新的 EL 表达式（可选）
     * @return 触发状态
     */
    @PostMapping("/refresh-event")
    public Result<String> publishRefreshEvent(
            @RequestParam(value = "chainId", required = false) String chainId,
            @RequestParam(value = "el", required = false) String el) {
        log.info("【动态规则】通过领域事件总线发布规则热更新：chainId={}, el={}", chainId, el);

        if (chainId != null && !chainId.isBlank() && el != null && !el.isBlank()) {
            eventPublisher.publishEvent(LiteflowRuleRefreshEvent.refreshChain(this, chainId, el));
            return Result.success("单链热更新事件已发布: chainId=" + chainId);
        } else {
            eventPublisher.publishEvent(LiteflowRuleRefreshEvent.refreshAll(this));
            return Result.success("全量规则源热重载事件已发布");
        }
    }
}
