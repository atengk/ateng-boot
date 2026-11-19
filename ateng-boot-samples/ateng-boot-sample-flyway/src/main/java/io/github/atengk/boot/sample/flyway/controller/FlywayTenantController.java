package io.github.atengk.boot.sample.flyway.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.flyway.enums.BatchExecutionPolicy;
import io.github.atengk.boot.flyway.model.BatchMigrationResult;
import io.github.atengk.boot.flyway.model.BatchOperationResult;
import io.github.atengk.boot.flyway.model.MigrationBaselineResult;
import io.github.atengk.boot.flyway.model.MigrationRepairResult;
import io.github.atengk.boot.flyway.model.MigrationScriptInfo;
import io.github.atengk.boot.flyway.model.MigrationTargetInfo;
import io.github.atengk.boot.flyway.template.FlywayTemplate;
import io.github.atengk.boot.sample.flyway.dto.BatchPolicyRequest;
import io.github.atengk.boot.sample.flyway.dto.TenantBatchBaselineRequest;
import io.github.atengk.boot.sample.flyway.dto.TenantMountRequest;
import io.github.atengk.boot.sample.flyway.dto.TenantOrderCreateRequest;
import io.github.atengk.boot.sample.flyway.service.FlywayPlatformBusinessService;
import io.github.atengk.boot.sample.flyway.vo.TenantMountResponse;
import io.github.atengk.boot.sample.flyway.vo.TenantOrderResponse;
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
 * SaaS 物理多租户批量迁移、动态挂载建表与跨租户数据隔离控制器
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Validated
@RestController
@RequestMapping("/sample/flyway/tenant")
@RequiredArgsConstructor
public class FlywayTenantController {

    private final FlywayTemplate flywayTemplate;
    private final FlywayPlatformBusinessService platformBusinessService;

    /**
     * 一键执行全部已注册 SaaS 物理租户库批量版本化迁移
     *
     * @return 全租户批量迁移聚合回执
     */
    @PostMapping("/migrate-all")
    public Result<BatchMigrationResult> migrateAllTenants() {
        return Result.success(flywayTemplate.migrateAllTenants());
    }

    /**
     * 一键执行全部已注册 SaaS 物理租户库只读一致性校验
     *
     * @return 全租户批量校验聚合回执
     */
    @PostMapping("/validate-all")
    public Result<BatchMigrationResult> validateAllTenants() {
        return Result.success(flywayTemplate.validateAllTenants());
    }

    /**
     * 批量预检全部已注册 SaaS 物理租户库待迁移脚本
     *
     * @param policy 可选批量容错策略
     * @return 全租户泛型批量预检回执
     */
    @GetMapping("/pending-all")
    public Result<BatchOperationResult<List<MigrationScriptInfo>>> pendingAllTenants(
            @RequestParam(required = false) BatchExecutionPolicy policy) {
        return Result.success(flywayTemplate.pendingAllTenants(policy));
    }

    /**
     * 容错聚合探查全部已注册 SaaS 物理租户库迁移历史与状态
     *
     * @param policy 可选批量容错策略
     * @return 全租户泛型批量状态聚合回执
     */
    @GetMapping("/info-all")
    public Result<BatchOperationResult<MigrationTargetInfo>> infoAllTenants(
            @RequestParam(required = false) BatchExecutionPolicy policy) {
        return Result.success(flywayTemplate.infoAllTenants(policy));
    }

    /**
     * 批量修复全部已注册 SaaS 物理租户库迁移历史表
     *
     * @param request 可选批量策略请求
     * @return 全租户泛型批量修复回执
     */
    @PostMapping("/repair-all")
    public Result<BatchOperationResult<MigrationRepairResult>> repairAllTenants(
            @Valid @RequestBody(required = false) BatchPolicyRequest request) {
        return Result.success(flywayTemplate.repairAllTenants(request != null ? request.getPolicy() : null));
    }

    /**
     * 批量对全部已注册 SaaS 物理租户库执行受控基线初始化
     *
     * @param request 可选基线参数与批量策略请求
     * @return 全租户泛型批量基线回执
     */
    @PostMapping("/baseline-all")
    public Result<BatchOperationResult<MigrationBaselineResult>> baselineAllTenants(
            @Valid @RequestBody(required = false) TenantBatchBaselineRequest request) {
        if (request == null) {
            return Result.success(flywayTemplate.baselineAllTenants());
        }
        return Result.success(flywayTemplate.baselineAllTenants(
                request.getVersion(), request.getDescription(), request.getPolicy()));
    }

    /**
     * 运行期动态挂载新物理租户库（自动触发 Flyway 建表并验证数据立即可读）
     *
     * @param request 动态挂载请求
     * @return 挂载与自动迁移结果
     */
    @PostMapping("/mount")
    public Result<TenantMountResponse> mountTenant(@Valid @RequestBody TenantMountRequest request) {
        return Result.success(platformBusinessService.mountTenantDataSource(request));
    }

    /**
     * 向指定物理租户分库写入业务订单
     *
     * @param request 订单创建请求
     * @return 订单响应视图
     */
    @PostMapping("/orders")
    public Result<TenantOrderResponse> createTenantOrder(@Valid @RequestBody TenantOrderCreateRequest request) {
        return Result.success(platformBusinessService.createTenantOrder(request));
    }

    /**
     * 查询指定物理租户分库下的订单清单（验证跨租户物理隔离）
     *
     * @param tenantId 租户标识
     * @return 订单响应视图列表
     */
    @GetMapping("/orders")
    public Result<List<TenantOrderResponse>> listTenantOrders(
            @RequestParam @NotBlank(message = "tenantId 不能为空") String tenantId) {
        return Result.success(platformBusinessService.listTenantOrders(tenantId));
    }
}
