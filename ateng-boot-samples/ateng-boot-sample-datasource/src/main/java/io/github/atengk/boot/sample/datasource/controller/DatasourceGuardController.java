package io.github.atengk.boot.sample.datasource.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.datasource.service.DatasourceTransactionGuardScenarioService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 事务假切源守卫与安全隔离控制器
 * <p>
 * 演示在 @Transactional 事务中尝试跨物理连接切源时被 DynamicDatasourceGuard 刚性拦截，
 * 以及采用 Propagation.REQUIRES_NEW 挂起外层事务安全开启独立新事务。
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@RestController
@RequestMapping("/sample/datasource/guard")
@RequiredArgsConstructor
public class DatasourceGuardController {

    private final DatasourceTransactionGuardScenarioService guardScenarioService;

    /**
     * 1. 模拟本地事务内违规切源 (将触发 DynamicDatasourceGuard 刚性拦截抛出异常)
     *
     * @param masterOrderNo 主库订单号 (默认 MASTER-TX-001)
     * @param tenantOrderNo 租户库订单号 (默认 TENANT-TX-001)
     * @return 正常情况下不会返回，预期抛出 DataSourceSwitchException
     */
    @PostMapping("/simulate-false-switch")
    public Result<String> simulateFalseSwitch(@RequestParam(defaultValue = "MASTER-TX-001") String masterOrderNo,
                                              @RequestParam(defaultValue = "TENANT-TX-001") String tenantOrderNo) {
        log.info("[GuardController] 发起事务内非法切源演练: masterOrderNo={}, tenantOrderNo={}", masterOrderNo, tenantOrderNo);
        guardScenarioService.transactionWithIllegalSwitch(masterOrderNo, tenantOrderNo);
        return Result.success("Illegal switch executed (unexpected)");
    }

    /**
     * 2. 演示通过 REQUIRES_NEW 挂起主事务安全切换数据源
     *
     * @param masterOrderNo 主库订单号 (默认 MASTER-NEW-001)
     * @param tenantOrderNo 租户库订单号 (默认 TENANT-NEW-001)
     * @return 操作成功结果
     */
    @PostMapping("/safe-switch-requires-new")
    public Result<String> safeSwitchRequiresNew(@RequestParam(defaultValue = "MASTER-NEW-001") String masterOrderNo,
                                                @RequestParam(defaultValue = "TENANT-NEW-001") String tenantOrderNo) {
        log.info("[GuardController] 发起安全挂起事务切源演练: masterOrderNo={}, tenantOrderNo={}", masterOrderNo, tenantOrderNo);
        guardScenarioService.transactionWithSafeRequiresNew(masterOrderNo, tenantOrderNo);
        return Result.success("Safe switch completed successfully using REQUIRES_NEW");
    }

    /**
     * 3. 演示同一物理数据源内事务安全重入切源
     *
     * @param orderNo 订单号 (默认 REENTRANT-001)
     * @return 操作成功结果
     */
    @PostMapping("/same-ds-reentrant")
    public Result<String> sameDsReentrant(@RequestParam(defaultValue = "REENTRANT-001") String orderNo) {
        log.info("[GuardController] 发起同源事务安全重入演练: orderNo={}", orderNo);
        guardScenarioService.transactionWithSameDsReentrant(orderNo);
        return Result.success("Reentrant same-datasource switch completed successfully");
    }
}
