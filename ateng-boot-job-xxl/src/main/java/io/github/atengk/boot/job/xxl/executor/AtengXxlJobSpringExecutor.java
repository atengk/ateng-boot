package io.github.atengk.boot.job.xxl.executor;

import com.xxl.job.core.executor.XxlJobExecutor;
import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import com.xxl.job.core.glue.GlueFactory;
import com.xxl.job.core.handler.IJobHandler;
import com.xxl.job.core.log.XxlJobFileAppender;
import com.xxl.job.core.thread.ExecutorRegistryThreadHelper;
import com.xxl.job.core.thread.JobLogFileCleanThreadHelper;
import com.xxl.job.core.thread.JobThread;
import com.xxl.job.core.thread.TriggerCallbackThreadHelper;
import io.github.atengk.boot.job.xxl.detector.ContainerIpDetector;
import io.github.atengk.boot.job.xxl.properties.XxlJobProperties;
import io.github.atengk.boot.job.xxl.resolver.XxlJobPortResolver;
import io.github.atengk.boot.job.xxl.server.AtengEmbedServer;
import io.github.atengk.boot.job.xxl.thread.AtengJobThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * XXL-Job 生产级加固型受管 Spring 执行器
 *
 * <p>全面重构增强官方 {@link XxlJobSpringExecutor}，具备以下工业级生产特性：
 * <ol>
 *     <li><b>受管线程池全托管</b>：重写线程注册逻辑，将裸线程治理注入 {@link AtengJobThread}；</li>
 *     <li><b>容器 IP 智能嗅探</b>：自适应探测 Kubernetes POD_IP / HOST_IP 与非虚拟真实网卡 IP；</li>
 *     <li><b>端口冲突顺延自愈</b>：自动探测端口占用并自愈顺延，支持 -1 随机端口分配；</li>
 *     <li><b>入站 Netty 防火墙</b>：集成 {@link AtengEmbedServer} 实现 CIDR 白名单与 AccessToken 快速失败防卫；</li>
 *     <li><b>三阶平滑停机排空</b>：实现 {@link SmartLifecycle}，优雅执行主动注销 -> 任务排空 -> 资源销毁三阶状态机。</li>
 * </ol>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class AtengXxlJobSpringExecutor extends XxlJobSpringExecutor implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AtengXxlJobSpringExecutor.class);

    private static final Field JOB_THREAD_REPOSITORY_FIELD;
    private static final Field CLEAN_HELPER_FIELD;
    private static final Field CALLBACK_HELPER_FIELD;
    private static final Field REGISTRY_HELPER_FIELD;
    private static final Field SINGLETON_INSTANCE_FIELD;
    private static final Method INIT_ADMIN_BIZ_LIST_METHOD;
    private static final Method SCAN_JOB_HANDLER_METHOD;

    static {
        JOB_THREAD_REPOSITORY_FIELD = findAndMakeAccessibleField(XxlJobExecutor.class, "jobThreadRepository");
        CLEAN_HELPER_FIELD = findAndMakeAccessibleField(XxlJobExecutor.class, "jobLogFileCleanThreadHelper");
        CALLBACK_HELPER_FIELD = findAndMakeAccessibleField(XxlJobExecutor.class, "triggerCallbackThreadHelper");
        REGISTRY_HELPER_FIELD = findAndMakeAccessibleField(XxlJobExecutor.class, "executorRegistryThreadHelper");
        SINGLETON_INSTANCE_FIELD = findAndMakeAccessibleField(XxlJobExecutor.class, "xxlJobExecutor");

        Method initAdminMethod = ReflectionUtils.findMethod(XxlJobExecutor.class, "initAdminBizList");
        if (initAdminMethod != null) {
            ReflectionUtils.makeAccessible(initAdminMethod);
        }
        INIT_ADMIN_BIZ_LIST_METHOD = initAdminMethod;

        Method scanMethod = ReflectionUtils.findMethod(XxlJobSpringExecutor.class, "scanJobHandlerMethod", ApplicationContext.class);
        if (scanMethod != null) {
            ReflectionUtils.makeAccessible(scanMethod);
        }
        SCAN_JOB_HANDLER_METHOD = scanMethod;
    }

    private final XxlJobProperties properties;
    private final AtengEmbedServer atengEmbedServer = new AtengEmbedServer();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean draining = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    private String ip;
    private String logPath = "logs/xxl-job/jobhandler";
    private int logRetentionDays = 30;

    private ApplicationContext applicationContext;
    private ExecutorRegistryThreadHelper registryThreadHelper;
    private TriggerCallbackThreadHelper callbackThreadHelper;
    private JobLogFileCleanThreadHelper cleanThreadHelper;

    public AtengXxlJobSpringExecutor() {
        this(new XxlJobProperties());
    }

    public AtengXxlJobSpringExecutor(XxlJobProperties properties) {
        this.properties = properties != null ? properties : new XxlJobProperties();
    }

    @Override
    public void setIp(String ip) {
        super.setIp(ip);
        this.ip = ip;
    }

    public String getIp() {
        return this.ip;
    }

    @Override
    public void setLogPath(String logPath) {
        super.setLogPath(logPath);
        this.logPath = logPath;
    }

    public String getLogPath() {
        return this.logPath;
    }

    @Override
    public void setLogRetentionDays(int logRetentionDays) {
        super.setLogRetentionDays(logRetentionDays);
        this.logRetentionDays = logRetentionDays;
    }

    public int getLogRetentionDays() {
        return this.logRetentionDays;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        super.setApplicationContext(applicationContext);
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterSingletonsInstantiated() {
        // 扫描 Spring 容器中的 JobHandler 方法并注入 Glue
        if (SCAN_JOB_HANDLER_METHOD != null && this.applicationContext != null) {
            ReflectionUtils.invokeMethod(SCAN_JOB_HANDLER_METHOD, this, this.applicationContext);
        }
        GlueFactory.refreshInstance(1);
        // 注意：不调用 super.afterSingletonsInstantiated() 以免重复触发未经加固的官方 start()
        // 统一由 SmartLifecycle.start() 触发受管生产级加固启动
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        try {
            // 1. 容器与网卡 IP 智能嗅探
            String detectedIp = ContainerIpDetector.detectIp(getIp());
            setIp(detectedIp);

            // 2. 端口冲突顺延自愈
            int resolvedPort = XxlJobPortResolver.resolvePort(
                    getPort(),
                    properties != null ? properties.getPortDetectionMaxRetry() : 100
            );
            setPort(resolvedPort);

            // 3. 计算并回填执行器注册 address
            if (!StringUtils.hasText(getAddress())) {
                setAddress("http://" + detectedIp + ":" + resolvedPort + "/");
            }

            // 4. 初始化日志路径与单例引用
            XxlJobFileAppender.initLogPath(getLogPath());
            if (SINGLETON_INSTANCE_FIELD != null) {
                SINGLETON_INSTANCE_FIELD.set(null, this);
            }

            // 5. 初始化调度中心客户端列表
            if (INIT_ADMIN_BIZ_LIST_METHOD != null) {
                INIT_ADMIN_BIZ_LIST_METHOD.invoke(this);
            }

            // 6. 启动日志清理后台线程
            this.cleanThreadHelper = new JobLogFileCleanThreadHelper();
            this.cleanThreadHelper.start(getLogRetentionDays());
            if (CLEAN_HELPER_FIELD != null) {
                CLEAN_HELPER_FIELD.set(this, this.cleanThreadHelper);
            }

            // 7. 启动调度结果回调后台线程
            this.callbackThreadHelper = new TriggerCallbackThreadHelper();
            this.callbackThreadHelper.start(this);
            if (CALLBACK_HELPER_FIELD != null) {
                CALLBACK_HELPER_FIELD.set(this, this.callbackThreadHelper);
            }

            // 8. 启动执行器注册心跳后台线程 (上报自身 IP/Port 至调度中心)
            this.registryThreadHelper = new ExecutorRegistryThreadHelper();
            this.registryThreadHelper.start(this);
            if (REGISTRY_HELPER_FIELD != null) {
                REGISTRY_HELPER_FIELD.set(this, this.registryThreadHelper);
            }

            // 9. 启动加固型 Netty 入站通信服务端 (CIDR 防火墙 + AccessToken 快速校验 + 停机排空)
            atengEmbedServer.start(this, properties, draining::get);

            log.info(">>>>>>>>>>> ateng-boot xxl-job executor started successfully [AppName: {}, Address: {}]",
                    getAppname(), getAddress());
        } catch (Exception e) {
            running.set(false);
            throw new RuntimeException("XXL-Job 执行器加固启动失败", e);
        }
    }

    @Override
    public void stop(Runnable callback) {
        if (!stopped.compareAndSet(false, true)) {
            if (callback != null) {
                callback.run();
            }
            return;
        }

        log.info(">>>>>>>>>>> 开始执行 XXL-Job 执行器三阶平滑停机排空状态机 (Graceful Shutdown) <<<<<<<<<<<");

        // 阶段一：主动注销，阻止新任务分发
        draining.set(true);
        if (this.registryThreadHelper != null) {
            try {
                this.registryThreadHelper.stop(this);
                log.info(">>>>> [阶段一/主动注销] 执行器已向调度中心发起主动注销 (registryRemove)，停止接收新任务。");
            } catch (Exception e) {
                log.error("执行器主动向调度中心注销失败", e);
            }
        }

        // 阶段二：等待运行中的任务在 drainTimeout 内排空
        drainInFlightJobs();

        // 阶段三：安全销毁 Netty 与底层后台线程
        destroyInternal();

        running.set(false);
        log.info(">>>>>>>>>>> XXL-Job 执行器三阶平滑停机排空完成 <<<<<<<<<<<");

        if (callback != null) {
            callback.run();
        }
    }

    @Override
    public void stop() {
        stop(() -> {});
    }

    @Override
    public void destroy() {
        // DisposableBean 幂等防护
        stop(() -> {});
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        // 返回 Integer.MAX_VALUE，确保停机时最早触发生命周期排空
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    /**
     * 等待运行中任务排空
     */
    private void drainInFlightJobs() {
        ConcurrentMap<Integer, JobThread> repository = getJobThreadRepository();
        if (repository == null || repository.isEmpty()) {
            log.info(">>>>> [阶段二/任务排空] 无活跃任务，无需等待排空。");
            return;
        }

        Duration timeout = properties != null && properties.getDrainTimeout() != null
                ? properties.getDrainTimeout()
                : Duration.ofSeconds(30);

        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        log.info(">>>>> [阶段二/任务排空] 正在等待活跃任务线程排空，最长等待超时时限: {} 秒", timeout.toSeconds());

        while (System.nanoTime() < deadlineNanos) {
            boolean hasRunningJobs = false;
            for (Map.Entry<Integer, JobThread> entry : repository.entrySet()) {
                JobThread jobThread = entry.getValue();
                if (jobThread != null && jobThread.isAlive() && jobThread.isRunningOrHasQueue()) {
                    hasRunningJobs = true;
                    break;
                }
            }

            if (!hasRunningJobs) {
                log.info(">>>>> [阶段二/任务排空] 所有在途调度任务已平滑执行完毕并排空。");
                return;
            }

            try {
                TimeUnit.MILLISECONDS.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("任务排空等待被外部中断");
                break;
            }
        }

        // 超过超时时限仍未排空，触发强中断熔断
        log.warn(">>>>> [阶段二/超时熔断] 活跃任务排空超时 ({} 秒)，对未完成任务线程触发中断退出", timeout.toSeconds());
        for (Map.Entry<Integer, JobThread> entry : repository.entrySet()) {
            JobThread jobThread = entry.getValue();
            if (jobThread != null && jobThread.isAlive()) {
                jobThread.toStop("Executor graceful drain timeout, forced interrupt.");
                jobThread.interrupt();
            }
        }
    }

    /**
     * 销毁底层通信与资源
     */
    private void destroyInternal() {
        try {
            atengEmbedServer.stop();
        } catch (Exception e) {
            log.error("停止 AtengEmbedServer 发生异常", e);
        }

        if (this.callbackThreadHelper != null) {
            try {
                this.callbackThreadHelper.stop();
            } catch (Exception e) {
                log.error("停止 TriggerCallbackThreadHelper 发生异常", e);
            }
        }

        if (this.cleanThreadHelper != null) {
            try {
                this.cleanThreadHelper.stop();
            } catch (Exception e) {
                log.error("停止 JobLogFileCleanThreadHelper 发生异常", e);
            }
        }

        log.info(">>>>> [阶段三/资源销毁] Netty 服务端及相关后台线程已彻底安全销毁。");
    }

    @Override
    public JobThread registJobThread(int jobId, IJobHandler handler, String removeOldReason) {
        AtengJobThread newJobThread = new AtengJobThread(jobId, handler);
        newJobThread.start();
        log.info(">>>>>>>>>>> ateng-boot xxl-job register AtengJobThread success, jobId:{}, handler:{}", jobId, handler);

        ConcurrentMap<Integer, JobThread> repository = getJobThreadRepository();
        if (repository != null) {
            JobThread oldJobThread = repository.put(jobId, newJobThread);
            if (oldJobThread != null) {
                oldJobThread.toStop(removeOldReason);
                oldJobThread.interrupt();
            }
        }

        return newJobThread;
    }

    @SuppressWarnings("unchecked")
    private ConcurrentMap<Integer, JobThread> getJobThreadRepository() {
        if (JOB_THREAD_REPOSITORY_FIELD != null) {
            try {
                return (ConcurrentMap<Integer, JobThread>) JOB_THREAD_REPOSITORY_FIELD.get(this);
            } catch (IllegalAccessException e) {
                log.error("获取 jobThreadRepository 反射字段失败", e);
            }
        }
        return null;
    }

    private static Field findAndMakeAccessibleField(Class<?> clazz, String fieldName) {
        Field field = ReflectionUtils.findField(clazz, fieldName);
        if (field != null) {
            ReflectionUtils.makeAccessible(field);
        }
        return field;
    }

}
