package io.github.atengk.boot.sample.web.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.web.filter.CachedBodyHttpServletRequest;
import io.github.atengk.boot.web.trace.TraceUtil;
import io.github.atengk.boot.web.util.IpUtil;
import io.github.atengk.boot.web.util.ServletUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Web 运行时流水线与基础设施场景控制器
 * 演示 TraceFilter 全链路追踪、虚拟线程调度、ContentCachingFilter 请求体重读、慢请求告警与运行时工具集
 *
 * @author Ateng
 * @since 2026-10-19
 */
@RestController
@RequestMapping("/api/sample/runtime")
public class WebRuntimePipelineSampleController {

    private final ObjectProvider<Executor> taskExecutorProvider;

    public WebRuntimePipelineSampleController(ObjectProvider<Executor> taskExecutorProvider) {
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
     * 慢请求监控模拟演示端点
     *
     * @param delayMs 模拟耗时毫秒数
     * @return 响应结果
     * @throws InterruptedException 挂起中断
     */
    @GetMapping("/slow")
    public Result<String> simulateSlowRequest(@RequestParam(defaultValue = "60") long delayMs) throws InterruptedException {
        Thread.sleep(delayMs);
        return Result.success("slow ok: " + delayMs + "ms");
    }

    /**
     * 慢请求排除白名单演示端点
     *
     * @return 正常响应
     * @throws InterruptedException 挂起中断
     */
    @GetMapping("/slow-excluded")
    public Result<String> simulateSlowExcluded() throws InterruptedException {
        Thread.sleep(60);
        return Result.success("excluded-ok");
    }

    /**
     * 客户端 IP 安全穿透提取演示端点
     *
     * @param request HTTP 请求
     * @return 客户端真实 IP
     */
    @GetMapping("/client-ip")
    public Result<String> getClientIp(HttpServletRequest request) {
        return Result.success(IpUtil.getClientIp(request));
    }

    /**
     * RFC 6266 文件安全流式下载演示端点
     *
     * @param response HTTP 响应
     * @throws IOException 下载写出异常
     */
    @GetMapping("/download")
    public void downloadSampleFile(HttpServletResponse response) throws IOException {
        byte[] data = "ateng-boot 工业级运行时下载测试内容".getBytes(StandardCharsets.UTF_8);
        ServletUtil.writeDownload(response, "测试样例文件.txt", data);
    }

    /**
     * 静态字典数据演示端点，用于配合 ETag 协商缓存与安全响应头校验
     *
     * @return 静态字典配置
     */
    @GetMapping("/cached-dict")
    public Result<Map<String, String>> getCachedDict() {
        Result<Map<String, String>> result = Result.success(Map.of("status", "ACTIVE", "locale", "zh_CN", "app", "ateng-boot-sample-web"));
        result.setTimestamp(1700000000000L);
        return result;
    }

    /**
     * 业务自定义响应头演示端点，验证 SecurityHeadersFilter 的 containsHeader 防覆盖保护机制
     *
     * @param response HTTP 响应
     * @return 业务响应载荷
     */
    @GetMapping("/custom-security-headers")
    public Result<String> getCustomSecurityHeaders(HttpServletResponse response) {
        response.setHeader("X-Frame-Options", "DENY");
        return Result.success("custom-headers-ok");
    }
}
