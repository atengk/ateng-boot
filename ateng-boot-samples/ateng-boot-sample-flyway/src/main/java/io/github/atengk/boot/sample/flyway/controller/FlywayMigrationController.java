package io.github.atengk.boot.sample.flyway.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.flyway.model.BatchMigrationResult;
import io.github.atengk.boot.flyway.model.MigrationOutcome;
import io.github.atengk.boot.flyway.model.MigrationScriptInfo;
import io.github.atengk.boot.flyway.model.MigrationValidationResult;
import io.github.atengk.boot.flyway.template.FlywayTemplate;
import io.github.atengk.boot.sample.flyway.dto.TargetOperationRequest;
import io.github.atengk.boot.sample.flyway.service.FlywayPlatformBusinessService;
import io.github.atengk.boot.sample.flyway.vo.AuditLogResponse;
import io.github.atengk.boot.sample.flyway.vo.TenantRegistryResponse;
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
 * 单库与静态业务库迁移、脚本预检与 MyBatis-Plus 屏障验证控制器
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Validated
@RestController
@RequestMapping("/sample/flyway/migration")
@RequiredArgsConstructor
public class FlywayMigrationController {

    private final FlywayTemplate flywayTemplate;
    private final FlywayPlatformBusinessService platformBusinessService;

    /**
     * 对指定目标库执行版本化迁移
     *
     * @param request 目标数据源请求
     * @return 迁移执行结果
     */
    @PostMapping("/execute")
    public Result<MigrationOutcome> executeMigration(@Valid @RequestBody TargetOperationRequest request) {
        return Result.success(flywayTemplate.migrate(request.getTargetKey()));
    }

    /**
     * 批量触发静态核心目标库（master 与 log_db）版本化迁移
     *
     * @return 批量迁移回执
     */
    @PostMapping("/execute-static")
    public Result<BatchMigrationResult> executeStaticTargets() {
        return Result.success(flywayTemplate.migrateTargets(List.of("master", "log_db")));
    }

    /**
     * 查询指定目标库尚未执行的待迁移脚本列表（Dry-Run 预检）
     *
     * @param targetKey 目标数据源标识（默认 master）
     * @return 待迁移脚本清单
     */
    @GetMapping("/pending")
    public Result<List<MigrationScriptInfo>> queryPendingScripts(
            @RequestParam(defaultValue = "master") @NotBlank(message = "targetKey 不能为空") String targetKey) {
        return Result.success(flywayTemplate.pending(targetKey));
    }

    /**
     * 对指定目标库执行只读一致性校验
     *
     * @param request 目标数据源请求
     * @return 校验结果
     */
    @PostMapping("/validate")
    public Result<MigrationValidationResult> validateTarget(@Valid @RequestBody TargetOperationRequest request) {
        return Result.success(flywayTemplate.validate(request.getTargetKey()));
    }

    /**
     * 通过 MyBatis-Plus 读取 master 库租户注册表（验证启动屏障就绪与零实体透出）
     *
     * @return 平台租户注册信息视图列表
     */
    @GetMapping("/platform/tenants")
    public Result<List<TenantRegistryResponse>> listPlatformTenants() {
        return Result.success(platformBusinessService.listPlatformTenants());
    }

    /**
     * 通过 MyBatis-Plus 读取独立静态业务库 log_db 审计日志
     *
     * @return 审计日志视图列表
     */
    @GetMapping("/audit-logs")
    public Result<List<AuditLogResponse>> listAuditLogs() {
        return Result.success(platformBusinessService.listAuditLogs());
    }
}
