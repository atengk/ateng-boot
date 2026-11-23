package io.github.atengk.boot.sample.flyway.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.flyway.enums.BatchExecutionPolicy;
import io.github.atengk.boot.flyway.model.BatchOperationResult;
import io.github.atengk.boot.flyway.model.MigrationBaselineResult;
import io.github.atengk.boot.flyway.model.MigrationCleanResult;
import io.github.atengk.boot.flyway.model.MigrationRepairResult;
import io.github.atengk.boot.flyway.model.MigrationSummary;
import io.github.atengk.boot.flyway.model.MigrationTargetInfo;
import io.github.atengk.boot.flyway.template.FlywayTemplate;
import io.github.atengk.boot.sample.flyway.dto.BaselineRequest;
import io.github.atengk.boot.sample.flyway.dto.BatchBaselineRequest;
import io.github.atengk.boot.sample.flyway.dto.BatchTargetsRequest;
import io.github.atengk.boot.sample.flyway.dto.DestructiveDdlDemoRequest;
import io.github.atengk.boot.sample.flyway.dto.IsolatedCredentialDemoRequest;
import io.github.atengk.boot.sample.flyway.dto.TargetOperationRequest;
import io.github.atengk.boot.sample.flyway.service.FlywayGovernanceService;
import io.github.atengk.boot.sample.flyway.vo.DestructiveDdlDemoResponse;
import io.github.atengk.boot.sample.flyway.vo.IsolatedCredentialDemoResponse;
import io.github.atengk.boot.sample.flyway.vo.MigrationHealthResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 迁移历史探查、安全守卫、基线修复与加密 DDL 凭据演练治理控制器
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Validated
@RestController
@RequestMapping("/sample/flyway/governance")
@RequiredArgsConstructor
public class FlywayGovernanceController {

    private final FlywayTemplate flywayTemplate;
    private final FlywayGovernanceService governanceService;

    /**
     * 查询指定目标库完整迁移历史与状态视图
     *
     * @param targetKey 目标数据源标识（默认 master）
     * @return 迁移历史视图对象
     */
    @GetMapping("/info")
    public Result<MigrationTargetInfo> info(
            @RequestParam(defaultValue = "master") @NotBlank(message = "targetKey 不能为空") String targetKey) {
        return Result.success(flywayTemplate.info(targetKey));
    }

    /**
     * 聚合查询全部受管控目标库（master、log_db 与全部租户库）迁移状态视图
     *
     * @return 全目标迁移状态列表
     */
    @GetMapping("/info-all")
    public Result<List<MigrationTargetInfo>> infoAll() {
        return Result.success(flywayTemplate.infoAll());
    }

    /**
     * 按容错策略聚合探查全部受管控目标库迁移状态并输出泛型回执
     *
     * @param policy 可选批量容错策略（默认继承静态容错策略）
     * @return 泛型批量状态聚合回执
     */
    @GetMapping("/info-batch")
    public Result<BatchOperationResult<MigrationTargetInfo>> infoAllBatch(
            @RequestParam(required = false) BatchExecutionPolicy policy) {
        return Result.success(flywayTemplate.infoAll(policy));
    }

    /**
     * 按容错策略聚合探查指定多目标数据源迁移历史与状态
     *
     * @param request 批量目标请求
     * @return 泛型批量状态聚合回执
     */
    @PostMapping("/info-targets")
    public Result<BatchOperationResult<MigrationTargetInfo>> infoTargets(
            @Valid @RequestBody BatchTargetsRequest request) {
        return Result.success(flywayTemplate.infoTargets(request.getTargetKeys(), request.getPolicy()));
    }

    /**
     * 查询最近一次全局迁移或校验摘要快照
     *
     * @return 迁移汇总摘要
     */
    @GetMapping("/summary")
    public Result<MigrationSummary> summary() {
        return Result.success(flywayTemplate.getLastMigrationSummary());
    }

    /**
     * 对指定目标库执行受控基线初始化（已有高版本历史时安全返回 0 警告或已基线状态）
     *
     * @param request 基线请求
     * @return 基线结果
     */
    @PostMapping("/baseline")
    public Result<MigrationBaselineResult> baseline(@Valid @RequestBody BaselineRequest request) {
        return Result.success(flywayTemplate.baseline(
                request.getTargetKey(), request.getVersion(), request.getDescription()));
    }

    /**
     * 对指定多目标数据源集合执行受控批量基线初始化
     *
     * @param request 批量基线请求
     * @return 泛型批量基线回执
     */
    @PostMapping("/baseline-targets")
    public Result<BatchOperationResult<MigrationBaselineResult>> baselineTargets(
            @Valid @RequestBody BatchBaselineRequest request) {
        return Result.success(flywayTemplate.baselineTargets(
                request.getTargetKeys(), request.getVersion(), request.getDescription(), request.getPolicy()));
    }

    /**
     * 对指定目标库执行历史表校验修复
     *
     * @param request 目标数据源请求
     * @return 修复结果
     */
    @PostMapping("/repair")
    public Result<MigrationRepairResult> repair(@Valid @RequestBody TargetOperationRequest request) {
        return Result.success(flywayTemplate.repair(request.getTargetKey()));
    }

    /**
     * 对指定多目标数据源集合执行批量历史表修复
     *
     * @param request 批量目标请求
     * @return 泛型批量修复回执
     */
    @PostMapping("/repair-targets")
    public Result<BatchOperationResult<MigrationRepairResult>> repairTargets(
            @Valid @RequestBody BatchTargetsRequest request) {
        return Result.success(flywayTemplate.repairTargets(request.getTargetKeys(), request.getPolicy()));
    }

    /**
     * 演示受保护环境与默认禁用策略下的 clean 硬熔断拦截（返回语义错误码 62003）
     *
     * @param request 目标数据源请求
     * @return 正常情况下将被拦截抛出 CLEAN_FORBIDDEN
     */
    @PostMapping("/clean")
    public Result<MigrationCleanResult> cleanWithGuard(@Valid @RequestBody TargetOperationRequest request) {
        return Result.success(flywayTemplate.clean(request.getTargetKey()));
    }

    /**
     * 演示隔离式 SM4 加密 DDL 凭据瞬态连接校验及零泄漏确定性关池
     *
     * @param request 隔离凭据演练请求
     * @return 瞬态执行与关池统计视图
     */
    @PostMapping("/isolated-credential/demo")
    public Result<IsolatedCredentialDemoResponse> demonstrateIsolatedCredential(
            @Valid @RequestBody IsolatedCredentialDemoRequest request) {
        return Result.success(governanceService.demonstrateIsolatedCredential(request));
    }

    /**
     * 演示待执行 SQL 高危破坏性 DDL 静态预检拦截（未授权返回 62010，带豁免注解或安全脚本放行）
     *
     * @param request 高危 DDL 预检演练请求
     * @return 预检演练响应视图
     */
    @PostMapping("/destructive-ddl-guard/demo")
    public Result<DestructiveDdlDemoResponse> demonstrateDestructiveDdlGuard(
            @Valid @RequestBody DestructiveDdlDemoRequest request) {
        return Result.success(governanceService.demonstrateDestructiveDdlGuard(request));
    }

    /**
     * 查询缓存感知 Flyway 迁移健康探针状态与底层探查计数
     *
     * @return 健康状态快照
     */
    @GetMapping("/health")
    public Result<MigrationHealthResponse> queryHealth() {
        return Result.success(governanceService.queryMigrationHealth());
    }
}
