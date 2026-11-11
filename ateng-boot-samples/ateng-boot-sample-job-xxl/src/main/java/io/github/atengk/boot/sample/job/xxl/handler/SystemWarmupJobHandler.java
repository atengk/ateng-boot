package io.github.atengk.boot.sample.job.xxl.handler;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import io.github.atengk.boot.job.xxl.annotation.AtengXxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * 系统冷启动预热巡检与基础调度任务处理器
 *
 * <p>演示微服务启动后的热点缓存预热、无锁 MDC traceId 全链路透传以及 SLF4J 动态日志镜像推送。</p>
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Component
public class SystemWarmupJobHandler {

    private static final Logger log = LoggerFactory.getLogger(SystemWarmupJobHandler.class);

    /**
     * 系统热点缓存预热与健康巡检任务
     */
    @XxlJob("systemWarmupJob")
    @AtengXxlJob(value = "systemWarmupJob", timeout = "5s")
    public void executeSystemWarmup() {
        String param = XxlJobHelper.getJobParam();
        String traceId = MDC.get("traceId");

        log.info(">>>>>> [SystemWarmupJobHandler] 执行系统热点缓存预热与巡检, 入参: {}, traceId: {}", param, traceId);
        XxlJobHelper.log("执行器日志镜像已同步: traceId={}", traceId);

        XxlJobHelper.handleSuccess("系统预热巡检任务执行完毕，入参: " + param);
    }

    /**
     * 兼容别名任务：基础问候任务
     */
    @XxlJob("simpleGreetingJob")
    @AtengXxlJob(value = "simpleGreetingJob", timeout = "5s")
    public void executeGreeting() {
        executeSystemWarmup();
    }

    /**
     * 灰度拦截跳过任务：演示被拦截器 preHandle 返回 false 优雅拦截并上报 200 SUCCESS
     */
    @XxlJob("canarySkipJob")
    @AtengXxlJob(value = "canarySkipJob")
    public void executeCanarySkip() {
        log.info(">>>>>> [SystemWarmupJobHandler] executeCanarySkip 被调用（若被拦截器拦截则不应输出此行）");
        XxlJobHelper.handleSuccess("灰度跳过任务未被拦截执行");
    }

    /**
     * 灰度正常审计任务：演示拦截器正常放行并在终态归档审计信息
     */
    @XxlJob("canaryNormalJob")
    @AtengXxlJob(value = "canaryNormalJob")
    public void executeCanaryNormal() {
        log.info(">>>>>> [SystemWarmupJobHandler] executeCanaryNormal 正在正常执行...");
        XxlJobHelper.handleSuccess("灰度正常任务执行成功");
    }

    /**
     * 可暂停治理任务：演示通过 Actuator 端点或 JobRuntimeGovernor 动态暂停与恢复执行
     */
    @XxlJob("pausableTaskJob")
    @AtengXxlJob(value = "pausableTaskJob")
    public void executePausableTask() {
        log.info(">>>>>> [SystemWarmupJobHandler] executePausableTask 正在正常执行...");
        XxlJobHelper.handleSuccess("可暂停任务正常执行完成");
    }
}
