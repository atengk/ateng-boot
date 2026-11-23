package io.github.atengk.boot.sample.datasource.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.datasource.loader.SyncResult;
import io.github.atengk.boot.datasource.model.DataSourcePoolStats;
import io.github.atengk.boot.sample.datasource.dto.AsyncContextPropagateRequest;
import io.github.atengk.boot.sample.datasource.dto.PasswordCipherVerifyRequest;
import io.github.atengk.boot.sample.datasource.service.DatasourceAsyncContextScenarioService;
import io.github.atengk.boot.sample.datasource.service.DatasourceDynamicLoaderScenarioService;
import io.github.atengk.boot.sample.datasource.service.DatasourceMetricsScenarioService;
import io.github.atengk.boot.sample.datasource.service.DatasourcePasswordSecurityScenarioService;
import io.github.atengk.boot.sample.datasource.vo.AsyncContextPropagateResponse;
import io.github.atengk.boot.sample.datasource.vo.PasswordCipherVerifyResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 动态多数据源运维度量、差量同步与安全可观测控制器
 * <p>
 * 提供实时物理连接池 Micrometer 运行快照查询、外部配置三向差量同步、核心白名单校验、
 * 零明文凭据密文探测校验以及五维异步上下文跨线程透传验证。
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@RestController
@RequestMapping("/sample/datasource/ops")
@RequiredArgsConstructor
public class DatasourceOpsController {

    private final DatasourceMetricsScenarioService metricsScenarioService;
    private final DatasourceDynamicLoaderScenarioService dynamicLoaderScenarioService;
    private final DatasourcePasswordSecurityScenarioService passwordSecurityScenarioService;
    private final DatasourceAsyncContextScenarioService asyncContextScenarioService;

    /**
     * 1. 查询所有已注册物理数据源连接池的实时运行指标快照 (Active/Idle/Total/Awaiting)
     *
     * @return 各数据源状态快照字典
     */
    @GetMapping("/pool-stats")
    public Result<Map<String, DataSourcePoolStats>> getAllPoolStats() {
        Map<String, DataSourcePoolStats> statsMap = metricsScenarioService.getAllPoolStats();
        return Result.success(statsMap);
    }

    /**
     * 2. 查询指定物理数据源的连接池运行快照
     *
     * @param dsKey 数据源标识 (如 master, slave)
     * @return 该连接池运行指标快照
     */
    @GetMapping("/pool-stats/{dsKey}")
    public Result<DataSourcePoolStats> getPoolStats(@PathVariable String dsKey) {
        DataSourcePoolStats stats = metricsScenarioService.getPoolStats(dsKey);
        return Result.success(stats);
    }

    /**
     * 3. 手动触发外部动态数据源智能三向差量同步 (Smart Diff)
     *
     * @return 差量同步结果模型 (包含 added, updated, removed 数据源列表)
     */
    @PostMapping("/sync-external")
    public Result<SyncResult> triggerExternalSync() {
        SyncResult result = dynamicLoaderScenarioService.triggerSync();
        log.info("[OpsController] 外部数据源差量同步触发完成: {}", result);
        return Result.success(result);
    }

    /**
     * 4. 校验指定数据源是否受核心白名单保护 (防误删防排空)
     *
     * @param dsKey 数据源标识
     * @return true 表示受保护
     */
    @GetMapping("/core-whitelist/check")
    public Result<Boolean> checkCoreWhitelist(@RequestParam String dsKey) {
        boolean protectedKey = dynamicLoaderScenarioService.isCoreProtected(dsKey);
        return Result.success(protectedKey);
    }

    /**
     * 5. 零明文凭据国密 SM4/AES 密文探测与瞬态解密校验（严禁在响应中回传任何明文密码）
     *
     * @param request 密文校验请求
     * @return 仅包含受支持状态、解密校验状态、识别算法与脱敏摘要的强类型响应
     */
    @PostMapping("/security/verify-cipher")
    public Result<PasswordCipherVerifyResponse> verifyCipherPassword(
            @Valid @RequestBody PasswordCipherVerifyRequest request) {
        PasswordCipherVerifyResponse response = passwordSecurityScenarioService.verifyCipherPassword(
                request.getEncryptedPassword());
        return Result.success(response);
    }

    /**
     * 6. 五维异步上下文跨线程透传验证（MDC TraceId / UserId / TenantId / IgnoreTenant / DataSourceKey）
     *
     * @param request 五维上下文透传验证请求
     * @return 子线程实际观测到的五维上下文快照
     */
    @PostMapping("/async-context/propagate")
    public Result<AsyncContextPropagateResponse> propagateAsyncContext(
            @Valid @RequestBody AsyncContextPropagateRequest request) {
        AsyncContextPropagateResponse response = asyncContextScenarioService.propagateFiveDimensionalContext(request);
        return Result.success(response);
    }
}
