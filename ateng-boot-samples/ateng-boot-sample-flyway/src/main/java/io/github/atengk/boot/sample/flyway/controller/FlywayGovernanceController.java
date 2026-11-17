package io.github.atengk.boot.sample.flyway.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.flyway.model.MigrationBaselineResult;
import io.github.atengk.boot.flyway.model.MigrationCleanResult;
import io.github.atengk.boot.flyway.model.MigrationRepairResult;
import io.github.atengk.boot.flyway.model.MigrationSummary;
import io.github.atengk.boot.flyway.model.MigrationTargetInfo;
import io.github.atengk.boot.flyway.template.FlywayTemplate;
import io.github.atengk.boot.sample.flyway.dto.BaselineRequest;
import io.github.atengk.boot.sample.flyway.dto.IsolatedCredentialDemoRequest;
import io.github.atengk.boot.sample.flyway.dto.TargetOperationRequest;
import io.github.atengk.boot.sample.flyway.service.FlywayGovernanceService;
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
     * 查询缓存感知 Flyway 迁移健康探针状态与底层探查计数
     *
     * @return 健康状态快照
     */
    @GetMapping("/health")
    public Result<MigrationHealthResponse> queryHealth() {
        return Result.success(governanceService.queryMigrationHealth());
    }
}
