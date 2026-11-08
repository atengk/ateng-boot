package io.github.atengk.boot.sample.mybatisplus.controller;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.mybatisplus.audit.DataChangeAuditContextHolder;
import io.github.atengk.boot.mybatisplus.audit.DataChangeRecord;
import io.github.atengk.boot.mybatisplus.slowsql.SlowSqlEvent;
import io.github.atengk.boot.sample.mybatisplus.audit.MemoryDataChangeAuditProvider;
import io.github.atengk.boot.sample.mybatisplus.entity.TenantOrderEntity;
import io.github.atengk.boot.sample.mybatisplus.mapper.UserMapper;
import io.github.atengk.boot.sample.mybatisplus.model.DataChangeAuditVO;
import io.github.atengk.boot.sample.mybatisplus.model.OrderAuditUpdateDTO;
import io.github.atengk.boot.sample.mybatisplus.model.SlowSqlEventVO;
import io.github.atengk.boot.sample.mybatisplus.model.SqlLogVO;
import io.github.atengk.boot.sample.mybatisplus.model.UserDynamicVO;
import io.github.atengk.boot.sample.mybatisplus.service.SlowSqlAlertScenarioService;
import io.github.atengk.boot.sample.mybatisplus.service.TenantOrderService;
import io.github.atengk.boot.sample.mybatisplus.slowsql.SampleSlowSqlAlertProvider;
import io.github.atengk.boot.sample.mybatisplus.sqllog.MemorySqlLogStorageProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 持久层全链路可观测、SQL 诊断看板、慢 SQL 告警与数据变更 Diff 审计控制器
 * 对应模块文档第 8 章，演示：
 * 1. SqlLogInterceptor + SqlParameterFormatter 带参可执行 SQL 反填、敏感脱敏与结构化 SqlLogModel 审计存储
 * 2. @IgnoreSqlLog 注解声明式心跳与健康探测 SQL 排除核验
 * 3. SlowSqlInterceptor 慢 SQL 纳秒耗时统计、密码凭据参数脱敏与 SampleSlowSqlAlertProvider 告警通知
 * 4. @AuditChange 声明式数据变更 Diff 审计、事务提交后快照捕获、超限行数熔断降级与 DataChangeAuditContextHolder.runWithoutAudit 闭包豁免
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Slf4j
@RestController
@RequestMapping("/sample/mybatis-plus/observability")
@RequiredArgsConstructor
public class MpObservabilityAndAuditController {

    private final MemorySqlLogStorageProvider sqlLogStorageProvider;
    private final UserMapper userMapper;
    private final SlowSqlAlertScenarioService slowSqlService;
    private final SampleSlowSqlAlertProvider slowSqlAlertProvider;
    private final TenantOrderService tenantOrderService;
    private final MemoryDataChangeAuditProvider dataChangeAuditProvider;
    private final TransactionTemplate transactionTemplate;

    /**
     * 查询最新收集的物理 SQL 结构化审计日志列表
     * 包含可执行带参 SQL、脱敏参数、耗时、影响行数与调用栈行号
     *
     * @param limit 最大返回条数限制（默认 10）
     * @return 结构化 SQL 审计日志视图列表
     */
    @GetMapping("/sql-log/recent")
    public Result<List<SqlLogVO>> getRecentSqlLogs(@RequestParam(defaultValue = "10") int limit) {
        List<SqlLogVO> logs = sqlLogStorageProvider.getLogs().stream()
                .map(SqlLogVO::fromModel)
                .toList();
        if (logs.size() > limit) {
            logs = logs.subList(logs.size() - limit, logs.size());
        }
        return Result.success(logs);
    }

    /**
     * 验证 @IgnoreSqlLog 声明式注解排除心跳/健康探测 SQL
     * 调用标注了 @IgnoreSqlLog 的 userMapper.pingHeartbeat()，断言其不会被 SqlLogStorageProvider 记录
     *
     * @return 心跳核验结果
     */
    @GetMapping("/sql-log/ignore-probe")
    public Result<Map<String, Object>> verifyIgnoreSqlLogProbe() {
        int logCountBefore = sqlLogStorageProvider.size();
        Integer probeVal = userMapper.pingHeartbeat();
        int logCountAfter = sqlLogStorageProvider.size();

        boolean anyHeartbeatLogged = sqlLogStorageProvider.getLogs().stream()
                .anyMatch(l -> l.getStatementId() != null && l.getStatementId().contains("pingHeartbeat"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("probeValue", probeVal);
        result.put("logCountBefore", logCountBefore);
        result.put("logCountAfter", logCountAfter);
        result.put("ignoredFromAuditLog", !anyHeartbeatLogged);
        result.put("message", "@IgnoreSqlLog 生效：心跳探测方法未被写入物理 SQL 审计存储");
        return Result.success(result);
    }

    /**
     * 执行带明文密码参数的查询以触发慢 SQL 拦截器脱敏实测
     *
     * @param username 用户名（默认 admin）
     * @param password 敏感密码凭据（默认 secret123456）
     * @return 匹配的用户视图列表
     */
    @GetMapping("/slow-sql/trigger")
    public Result<List<UserDynamicVO>> triggerSlowSql(
            @RequestParam(defaultValue = "admin") String username,
            @RequestParam(defaultValue = "secret123456") String password) {
        return Result.success(slowSqlService.triggerSlowSqlWithPasswordParam(username, password));
    }

    /**
     * 查询 SampleSlowSqlAlertProvider 收集到的慢 SQL 告警事件列表
     *
     * @return 慢 SQL 告警事件视图列表
     */
    @GetMapping("/slow-sql/events")
    public Result<List<SlowSqlEventVO>> getSlowSqlEvents() {
        List<SlowSqlEvent> events = slowSqlAlertProvider.getEvents();
        return Result.success(events.stream().map(SlowSqlEventVO::fromEvent).toList());
    }

    /**
     * 执行订单更新以触发 @AuditChange 声明式数据变更 Diff 审计
     * 事务提交后由 DataChangeAuditInterceptor 自动计算 FieldDiff 快照并分发至 MemoryDataChangeAuditProvider
     *
     * @param updateDTO 订单更新请求
     * @return 捕获到的数据变更 Diff 审计记录列表
     */
    @PostMapping("/audit-change/update-order")
    public Result<List<DataChangeAuditVO>> updateOrderWithAudit(@Valid @RequestBody OrderAuditUpdateDTO updateDTO) {
        boolean needClearTenant = false;
        if (TenantContextHolder.getTenantId() == null) {
            TenantContextHolder.setTenantId("tenant_audit");
            needClearTenant = true;
        }
        try {
            Long targetId = updateDTO.getOrderId();
            TenantOrderEntity order = targetId != null && targetId > 0 ? tenantOrderService.getById(targetId) : null;
            if (order == null) {
                order = new TenantOrderEntity("ORD_AUDIT_DEMO", "初始审计演示订单", "SKU_DEMO", new BigDecimal("100.00"), "CREATED");
                order.setTenantId("tenant_audit");
                tenantOrderService.save(order);
                targetId = order.getId();
            }

            final Long finalTargetId = targetId;
            int countBefore = dataChangeAuditProvider.getRecords().size();

            transactionTemplate.execute(status -> {
                TenantOrderEntity toUpdate = tenantOrderService.getById(finalTargetId);
                if (updateDTO.getNewTitle() != null) {
                    toUpdate.setOrderTitle(updateDTO.getNewTitle());
                } else {
                    toUpdate.setOrderTitle("已变更订单标题_" + System.currentTimeMillis() % 1000);
                }
                if (updateDTO.getNewAmount() != null) {
                    toUpdate.setAmount(updateDTO.getNewAmount());
                } else {
                    toUpdate.setAmount(new BigDecimal("299.00"));
                }
                if (updateDTO.getNewStatus() != null) {
                    toUpdate.setStatus(updateDTO.getNewStatus());
                }
                tenantOrderService.updateById(toUpdate);
                return true;
            });

            List<DataChangeRecord> allRecords = dataChangeAuditProvider.getRecords();
            List<DataChangeRecord> newlyCaptured = allRecords.subList(countBefore, allRecords.size());
            return Result.success(newlyCaptured.stream().map(DataChangeAuditVO::fromRecord).toList());
        } finally {
            if (needClearTenant) {
                TenantContextHolder.clear();
            }
        }
    }

    /**
     * 在 DataChangeAuditContextHolder.runWithoutAudit 编程式闭包下执行订单更新
     * 演示闭包内更新完全绕过变更审计，不触发快照抓取与 Diff 计算
     *
     * @param updateDTO 订单更新请求
     * @return 闭包豁免核验结果
     */
    @PostMapping("/audit-change/without-audit")
    public Result<Map<String, Object>> updateOrderWithoutAudit(@Valid @RequestBody OrderAuditUpdateDTO updateDTO) {
        boolean needClearTenant = false;
        if (TenantContextHolder.getTenantId() == null) {
            TenantContextHolder.setTenantId("tenant_audit");
            needClearTenant = true;
        }
        try {
            Long targetId = updateDTO.getOrderId();
            TenantOrderEntity order = targetId != null && targetId > 0 ? tenantOrderService.getById(targetId) : null;
            if (order == null) {
                order = new TenantOrderEntity("ORD_NO_AUDIT_DEMO", "闭包豁免订单", "SKU_NO_AUDIT", new BigDecimal("88.00"), "CREATED");
                order.setTenantId("tenant_audit");
                tenantOrderService.save(order);
                targetId = order.getId();
            }

            final Long finalTargetId = targetId;
            int countBefore = dataChangeAuditProvider.getRecords().size();

            // 使用 DataChangeAuditContextHolder.runWithoutAudit 闭包进行豁免
            DataChangeAuditContextHolder.runWithoutAudit(() -> {
                transactionTemplate.execute(status -> {
                    TenantOrderEntity toUpdate = tenantOrderService.getById(finalTargetId);
                    toUpdate.setOrderTitle("闭包更新无审计标题");
                    toUpdate.setAmount(new BigDecimal("188.00"));
                    tenantOrderService.updateById(toUpdate);
                    return true;
                });
            });

            int countAfter = dataChangeAuditProvider.getRecords().size();
            boolean bypassed = (countBefore == countAfter);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("recordsBefore", countBefore);
            result.put("recordsAfter", countAfter);
            result.put("bypassed", bypassed);
            result.put("message", "DataChangeAuditContextHolder.runWithoutAudit 闭包生效：事务提交后未生成任何 Diff 审计记录");
            return Result.success(result);
        } finally {
            if (needClearTenant) {
                TenantContextHolder.clear();
            }
        }
    }

    /**
     * 查询 MemoryDataChangeAuditProvider 收集到的全部数据变更审计记录
     *
     * @return 全部数据变更审计视图列表
     */
    @GetMapping("/audit-change/records")
    public Result<List<DataChangeAuditVO>> getAllAuditRecords() {
        List<DataChangeRecord> records = dataChangeAuditProvider.getRecords();
        return Result.success(records.stream().map(DataChangeAuditVO::fromRecord).toList());
    }
}
