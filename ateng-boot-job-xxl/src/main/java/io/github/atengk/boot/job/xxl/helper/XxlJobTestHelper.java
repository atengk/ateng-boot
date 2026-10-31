package io.github.atengk.boot.job.xxl.helper;

import com.xxl.job.core.context.XxlJobContext;
import com.xxl.job.core.executor.XxlJobExecutor;
import com.xxl.job.core.handler.IJobHandler;
import com.xxl.job.core.handler.annotation.XxlJob;
import com.xxl.job.core.handler.impl.MethodJobHandler;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.TenantContextHolder.TenantSnapshot;
import io.github.atengk.boot.core.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * XXL-Job 脱网本地调试与端到端测试辅助门面
 *
 * <p>用于在无调度中心 (Admin) 运行的离线自洽单测与本地开发环境下，一键手动模拟触发执行任意已注册的 {@link XxlJob} 任务：
 * <ul>
 *     <li><b>双模 Handler 探测</b>：优先从 {@link XxlJobExecutor} 运行库提取，未就绪时自动穿透 Spring 容器内省探测；</li>
 *     <li><b>四维上下文自动装配</b>：自动在当前工作线程注入合法的 {@link XxlJobContext} 分片、参数与追踪快照；</li>
 *     <li><b>确定性清理恢复</b>：无论执行成功还是抛出异常，在 {@code finally} 中严格执行上下文清理与现场还原。</li>
 * </ul></p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class XxlJobTestHelper implements ApplicationContextAware {

    private static final Logger log = LoggerFactory.getLogger(XxlJobTestHelper.class);

    private static ApplicationContext applicationContext;
    private static final ConcurrentMap<String, IJobHandler> FALLBACK_HANDLER_REPOSITORY = new ConcurrentHashMap<>();

    @Override
    public void setApplicationContext(ApplicationContext context) throws BeansException {
        applicationContext = context;
        scanAndRegisterSpringHandlers(context);
    }

    /**
     * 触发执行任务 (仅指定 handler)
     *
     * @param jobHandler 任务 Handler 名称
     * @return 执行诊断报告
     */
    public static XxlJobTestResult trigger(String jobHandler) {
        return trigger(jobHandler, null, 0, 1, null);
    }

    /**
     * 触发执行任务 (指定 handler 与入参)
     *
     * @param jobHandler 任务 Handler 名称
     * @param param      执行入参
     * @return 执行诊断报告
     */
    public static XxlJobTestResult trigger(String jobHandler, String param) {
        return trigger(jobHandler, param, 0, 1, null);
    }

    /**
     * 触发执行任务 (指定分片广播参数)
     *
     * @param jobHandler 任务 Handler 名称
     * @param param      执行入参
     * @param shardIndex 当前分片索引 (从 0 开始)
     * @param shardTotal 分片总数
     * @return 执行诊断报告
     */
    public static XxlJobTestResult trigger(String jobHandler, String param, int shardIndex, int shardTotal) {
        return trigger(jobHandler, param, shardIndex, shardTotal, null);
    }

    /**
     * 触发执行任务 (全维参数)
     *
     * @param jobHandler 任务 Handler 名称
     * @param param      执行入参
     * @param shardIndex 当前分片索引 (从 0 开始)
     * @param shardTotal 分片总数
     * @param tenantId   显式指定的租户标识 (可为 null)
     * @return 执行诊断报告
     */
    public static XxlJobTestResult trigger(String jobHandler, String param, int shardIndex, int shardTotal, String tenantId) {
        Assert.hasText(jobHandler, "jobHandler 不能为空");

        IJobHandler handler = loadHandler(jobHandler);
        if (handler == null) {
            throw new BusinessException("未找到名称为 [" + jobHandler + "] 的 XXL-Job Handler，请确认任务是否声明了 @XxlJob(\"" + jobHandler + "\")");
        }

        long startNs = System.nanoTime();
        long now = System.currentTimeMillis();

        // 备份当前上下文
        XxlJobContext previousContext = XxlJobContext.getXxlJobContext();
        TenantSnapshot previousTenantSnapshot = TenantContextHolder.capturePrevious();

        XxlJobContext testContext = new XxlJobContext(
                1L,
                param != null ? param : "",
                now,
                now,
                "test-" + jobHandler + ".log",
                Math.max(0, shardIndex),
                Math.max(1, shardTotal)
        );

        if (StringUtils.hasText(tenantId)) {
            TenantContextHolder.setTenantId(tenantId);
        }

        try {
            XxlJobContext.setXxlJobContext(testContext);
            log.info(">>>>>> [脱网调试] 开始手动触发 XXL-Job 任务 [handler: {}, param: {}, shard: {}/{}]",
                    jobHandler, param, shardIndex, shardTotal);

            handler.execute();

            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            int handleCode = testContext.getHandleCode() > 0 ? testContext.getHandleCode() : XxlJobContext.HANDLE_CODE_SUCCESS;
            String handleMsg = testContext.getHandleMsg();

            log.info("<<<<<< [脱网调试] XXL-Job 任务 [{}] 执行成功 (耗时: {}ms, code: {})", jobHandler, durationMs, handleCode);
            return new XxlJobTestResult(true, handleCode, handleMsg, durationMs, null);
        } catch (Throwable t) {
            Throwable root = (t instanceof java.lang.reflect.InvocationTargetException ite && ite.getTargetException() != null)
                    ? ite.getTargetException()
                    : t;
            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            log.error("<<<<<< [脱网调试] XXL-Job 任务 [{}] 执行失败 (耗时: {}ms): {}", jobHandler, durationMs, root.getMessage(), root);
            return new XxlJobTestResult(false, XxlJobContext.HANDLE_CODE_FAIL, root.getMessage(), durationMs, root);
        } finally {
            XxlJobContext.setXxlJobContext(previousContext);
            TenantContextHolder.restore(previousTenantSnapshot);
        }
    }

    /**
     * 获取所有已发现注册的 JobHandler 名称列表
     */
    public static Map<String, IJobHandler> getLoadedHandlers() {
        Map<String, IJobHandler> map = new ConcurrentHashMap<>(FALLBACK_HANDLER_REPOSITORY);
        try {
            XxlJobExecutor executor = XxlJobExecutor.getInstance();
            if (executor != null) {
                // 通过内省补充
            }
        } catch (Throwable ignored) {
            // XxlJobExecutor 实例未初始化，忽略
        }
        return Collections.unmodifiableMap(map);
    }

    private static IJobHandler loadHandler(String jobHandler) {
        // 1. 优先从官方 XxlJobExecutor 实例查找
        try {
            XxlJobExecutor executor = XxlJobExecutor.getInstance();
            if (executor != null) {
                IJobHandler handler = executor.loadJobHandler(jobHandler);
                if (handler != null) {
                    return handler;
                }
            }
        } catch (Throwable ignored) {
            // XxlJobExecutor 实例未初始化，平滑降级至 Spring 容器查找
        }

        // 2. 从本地降级仓库查找
        IJobHandler fallback = FALLBACK_HANDLER_REPOSITORY.get(jobHandler);
        if (fallback != null) {
            return fallback;
        }

        // 3. 尝试从 Spring 容器即时内省
        if (applicationContext != null) {
            scanAndRegisterSpringHandlers(applicationContext);
            return FALLBACK_HANDLER_REPOSITORY.get(jobHandler);
        }

        return null;
    }

    private static synchronized void scanAndRegisterSpringHandlers(ApplicationContext context) {
        if (context == null) {
            return;
        }
        String[] beanNames = context.getBeanNamesForType(Object.class);
        for (String beanName : beanNames) {
            Object bean;
            try {
                bean = context.getBean(beanName);
            } catch (Exception e) {
                continue;
            }
            Map<Method, XxlJob> annotatedMethods = MethodIntrospector.selectMethods(
                    bean.getClass(),
                    (MethodIntrospector.MetadataLookup<XxlJob>) method -> AnnotatedElementUtils.findMergedAnnotation(method, XxlJob.class)
            );

            for (Map.Entry<Method, XxlJob> entry : annotatedMethods.entrySet()) {
                Method method = entry.getKey();
                XxlJob xxlJob = entry.getValue();
                String name = xxlJob.value();
                if (StringUtils.hasText(name)) {
                    Method initMethod = null;
                    Method destroyMethod = null;
                    if (StringUtils.hasText(xxlJob.init())) {
                        try {
                            initMethod = bean.getClass().getDeclaredMethod(xxlJob.init());
                        } catch (NoSuchMethodException ignored) {}
                    }
                    if (StringUtils.hasText(xxlJob.destroy())) {
                        try {
                            destroyMethod = bean.getClass().getDeclaredMethod(xxlJob.destroy());
                        } catch (NoSuchMethodException ignored) {}
                    }
                    MethodJobHandler handler = new MethodJobHandler(bean, method, initMethod, destroyMethod);
                    FALLBACK_HANDLER_REPOSITORY.putIfAbsent(name, handler);
                }
            }
        }
    }

    /**
     * 任务脱网执行诊断结果记录
     *
     * @param success    是否成功执行
     * @param handleCode 响应码 (200 为成功，500 为失败)
     * @param handleMsg  响应文案
     * @param durationMs 执行耗时 (毫秒)
     * @param throwable  执行异常 (若有)
     */
    public record XxlJobTestResult(
            boolean success,
            int handleCode,
            String handleMsg,
            long durationMs,
            Throwable throwable
    ) {
        public boolean isSuccess() {
            return success;
        }
    }
}
