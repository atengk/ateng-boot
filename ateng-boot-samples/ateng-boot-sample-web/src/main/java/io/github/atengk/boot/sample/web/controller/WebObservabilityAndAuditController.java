package io.github.atengk.boot.sample.web.controller;

import io.github.atengk.boot.core.audit.OperateTypeEnum;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.web.dto.AuditUserDTO;
import io.github.atengk.boot.sample.web.vo.UserRegisterVO;
import io.github.atengk.boot.web.annotation.OperateLog;
import io.github.atengk.boot.web.annotation.WebLog;
import io.github.atengk.boot.web.filter.CachedBodyHttpServletRequest;
import io.github.atengk.boot.web.trace.TraceUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * 全链路追踪、请求体缓存与双向可观测审计第二领域场景控制器
 * 覆盖 TraceFilter 链路追踪、虚拟线程调度、ContentCachingFilter 请求体重读、PayloadLoggingFilter 双向采样、
 * OperateLog 结构化审计与出参动态脱敏、WebLog 接口耗时以及 SlowRequestFilter 慢请求告警
 *
 * @author Ateng
 * @since 2026-11-07
 */
@RestController
@RequestMapping("/sample/web/observability")
public class WebObservabilityAndAuditController {

    private final ObjectProvider<Executor> taskExecutorProvider;

    public WebObservabilityAndAuditController(ObjectProvider<Executor> taskExecutorProvider) {
        this.taskExecutorProvider = taskExecutorProvider;
    }

    /**
     * 获取当前 HTTP 请求上下文中的 TraceId
     *
     * @return 全链路追踪 ID
     */
    @GetMapping("/trace")
    public Result<String> getTraceId() {
        return Result.success(TraceUtil.getTraceId());
    }

    /**
     * 探查当前容器受管执行器是否调度于 Java 21+ 虚拟线程环境中
     *
     * @return 线程运行状态快照
     * @throws Exception 探测中断
     */
    @GetMapping("/virtual-thread")
    public Result<Map<String, Object>> checkVirtualThread() throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        Executor executor = taskExecutorProvider.getIfAvailable();
        if (executor != null) {
            CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(
                    () -> isVirtualThread(Thread.currentThread()),
                    executor
            );
            data.put("isVirtual", future.get(3, TimeUnit.SECONDS));
        } else {
            data.put("isVirtual", isVirtualThread(Thread.currentThread()));
        }
        data.put("threadName", Thread.currentThread().getName());
        data.put("traceId", TraceUtil.getTraceId());
        return Result.success(data);
    }

    private static boolean isVirtualThread(Thread thread) {
        try {
            Method method = Thread.class.getMethod("isVirtual");
            return (boolean) method.invoke(thread);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 验证 ContentCachingFilter 请求体可重复消费能力
     *
     * @param request HTTP 请求
     * @return 重复读取验证结果
     * @throws IOException 读取异常
     */
    @PostMapping("/body-cache")
    public Result<Map<String, Object>> testBodyCache(HttpServletRequest request) throws IOException {
        // 第一次读取
        byte[] firstRead = request.getInputStream().readAllBytes();
        // 第二次在同一次请求中再次读取输入流
        byte[] secondRead = request.getInputStream().readAllBytes();

        boolean isCachedWrapper = request instanceof CachedBodyHttpServletRequest;
        byte[] cachedBodyBytes = isCachedWrapper
                ? ((CachedBodyHttpServletRequest) request).getCachedBody()
                : new byte[0];

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("firstReadLength", firstRead.length);
        map.put("secondReadLength", secondRead.length);
        map.put("isCachedWrapper", isCachedWrapper);
        map.put("cachedLength", cachedBodyBytes.length);
        map.put("contentMatch", firstRead.length == secondRead.length);
        return Result.success(map);
    }

    /**
     * HTTP Payload 双向采样与敏感凭据打码体验端点
     * 自动触发 PayloadLoggingFilter [HTTP_PAYLOAD] 采样日志，敏感字段正则掩码打码，4KB 截断防爆
     *
     * @param payload 请求报文
     * @return 回显报文
     */
    @PostMapping("/payload-echo")
    public Result<Map<String, Object>> echoPayload(@RequestBody Map<String, Object> payload) {
        return Result.success(payload);
    }

    /**
     * HTTP Payload 采样排除白名单端点
     * 匹配 exclude-urls 白名单，跳过 PayloadLoggingFilter 采样日志打印
     *
     * @param payload 请求报文
     * @return 回显报文
     */
    @PostMapping("/payload-excluded")
    public Result<Map<String, Object>> excludedPayload(@RequestBody Map<String, Object> payload) {
        return Result.success(payload);
    }

    /**
     * 新增操作员操作审计（入参敏感字段 password 自动排除，出参手机号动态脱敏，JVM 内存实体保持真明文）
     *
     * @param dto 审计操作员载荷
     * @return 操作员视图（包含动态脱敏手机号）
     */
    @PostMapping("/audit/operator")
    @OperateLog(
            module = "权限中心",
            type = OperateTypeEnum.INSERT,
            description = "新增系统操作员",
            excludeParams = {"password"}
    )
    public Result<UserRegisterVO> createOperator(@Valid @RequestBody AuditUserDTO dto) {
        UserRegisterVO vo = UserRegisterVO.builder()
                .id(1846182948192841920L)
                .name(dto.getUsername())
                .age(28)
                .email(dto.getEmail() != null ? dto.getEmail() : "operator@example.com")
                .phone(dto.getPhone() != null ? dto.getPhone() : "13812345678")
                .build();
        return Result.success(vo);
    }

    /**
     * 查询操作员详情操作审计（出参联动 @FieldSensitive 脱敏，内存对象保持真明文）
     *
     * @param id 操作员主键
     * @return 操作员视图
     */
    @GetMapping("/audit/operator/{id}")
    @OperateLog(
            module = "权限中心",
            type = OperateTypeEnum.QUERY,
            description = "查询操作员详情"
    )
    public Result<UserRegisterVO> getOperator(@PathVariable Long id) {
        UserRegisterVO vo = UserRegisterVO.builder()
                .id(id)
                .name("系统管理员")
                .age(30)
                .email("admin@example.com")
                .phone("13912345678")
                .build();
        return Result.success(vo);
    }

    /**
     * 业务操作异常审计（切面准确捕获异常状态并原样重抛，保障因果链透明）
     *
     * @return 无返回值
     */
    @GetMapping("/audit/error")
    @OperateLog(
            module = "权限中心",
            type = OperateTypeEnum.OTHER,
            description = "异常操作审计"
    )
    public Result<Void> triggerAuditError() {
        throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), "操作员状态已冻结，禁止变更");
    }

    /**
     * WebLog 轻量级接口耗时与入参审计演示端点（消除测试桩字面量）
     *
     * @param dto 审计用户载荷
     * @return 业务响应载荷
     */
    @PostMapping("/weblog")
    @WebLog(value = "操作员档案更新耗时审计", excludeParams = {"password"}, printCostTime = true)
    public Result<Map<String, Object>> webLogDemo(@Valid @RequestBody AuditUserDTO dto) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operator", dto.getUsername());
        data.put("status", "AUDITED");
        data.put("traceId", TraceUtil.getTraceId());
        return Result.success(data);
    }

    /**
     * 无显式 excludeParams 的 @OperateLog 默认敏感参数基线脱敏演示端点
     *
     * @param payload 请求载荷（可包含 password/secret/token/authorization 等默认敏感字段）
     * @return 非敏感确认摘要
     */
    @PostMapping("/audit/default-mask")
    @OperateLog(module = "权限中心", type = OperateTypeEnum.OTHER, description = "默认基线操作审计")
    public Result<Map<String, Object>> operateLogDefaultMask(@RequestBody Map<String, Object> payload) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("account", payload.get("account"));
        data.put("status", "MASKED");
        return Result.success(data);
    }

    /**
     * 无显式 excludeParams 的 @WebLog 默认敏感参数基线脱敏演示端点
     *
     * @param payload 请求载荷（可包含 password/secret/token/authorization 等默认敏感字段）
     * @return 非敏感确认摘要
     */
    @PostMapping("/weblog/default-mask")
    @WebLog(value = "默认基线WebLog审计", printCostTime = true)
    public Result<Map<String, Object>> webLogDefaultMask(@RequestBody Map<String, Object> payload) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("account", payload.get("account"));
        data.put("traceId", TraceUtil.getTraceId());
        return Result.success(data);
    }

    /**
     * 慢请求监控模拟端点（模拟财务报表耗时查询，超出阈值触发 [SLOW_REQUEST] WARN 日志告警）
     *
     * @param delayMs 模拟耗时毫秒数
     * @return 业务报表摘要响应
     * @throws InterruptedException 挂起中断
     */
    @GetMapping("/slow-query")
    public Result<Map<String, Object>> slowQueryDemo(@RequestParam(defaultValue = "60") long delayMs) throws InterruptedException {
        Thread.sleep(delayMs);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reportName", "2026年度财务汇总对账单");
        data.put("records", 100000);
        data.put("costMs", delayMs);
        data.put("status", "SUCCESS");
        return Result.success(data);
    }

    /**
     * 慢请求排除白名单演示端点（匹配 exclude-urls 白名单，即使耗时超阈值也不触发告警）
     *
     * @param delayMs 模拟耗时毫秒数
     * @return 正常放行响应
     * @throws InterruptedException 挂起中断
     */
    @GetMapping("/slow-excluded")
    public Result<String> slowExcludedDemo(@RequestParam(defaultValue = "60") long delayMs) throws InterruptedException {
        Thread.sleep(delayMs);
        return Result.success("excluded-ok");
    }
}
