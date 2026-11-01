package io.github.atengk.boot.sample.job.xxl.handler;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import io.github.atengk.boot.job.xxl.annotation.AtengXxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * 基础定时任务处理器样例
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Component
public class SimpleDemoJobHandler {

    private static final Logger log = LoggerFactory.getLogger(SimpleDemoJobHandler.class);

    /**
     * 基础问候任务：演示 MDC traceId 自动透传与 SLF4J 日志动态镜像
     */
    @XxlJob("simpleGreetingJob")
    @AtengXxlJob(value = "simpleGreetingJob", timeout = "5s")
    public void executeGreeting() {
        String param = XxlJobHelper.getJobParam();
        String traceId = MDC.get("traceId");

        log.info(">>>>>> [SimpleDemoJobHandler] 执行定时问候任务, 入参: {}, traceId: {}", param, traceId);
        XxlJobHelper.log("执行器日志镜像已同步: traceId={}", traceId);

        XxlJobHelper.handleSuccess("问候任务执行完毕，入参: " + param);
    }

    /**
     * 灰度拦截跳过任务：演示被拦截器 preHandle 返回 false 优雅拦截并上报 200 SUCCESS
     */
    @XxlJob("canarySkipJob")
    @AtengXxlJob(value = "canarySkipJob")
    public void executeCanarySkip() {
        log.info(">>>>>> [SimpleDemoJobHandler] executeCanarySkip 被调用（若被拦截器拦截则不应输出此行）");
        XxlJobHelper.handleSuccess("灰度跳过任务未被拦截执行");
    }

    /**
     * 灰度正常审计任务：演示拦截器正常放行并在终态归档审计信息
     */
    @XxlJob("canaryNormalJob")
    @AtengXxlJob(value = "canaryNormalJob")
    public void executeCanaryNormal() {
        log.info(">>>>>> [SimpleDemoJobHandler] executeCanaryNormal 正在正常执行...");
        XxlJobHelper.handleSuccess("灰度正常任务执行成功");
    }

    /**
     * 可暂停治理任务：演示通过 Actuator 端点或 JobRuntimeGovernor 动态暂停与恢复执行
     */
    @XxlJob("pausableTaskJob")
    @AtengXxlJob(value = "pausableTaskJob")
    public void executePausableTask() {
        log.info(">>>>>> [SimpleDemoJobHandler] executePausableTask 正在正常执行...");
        XxlJobHelper.handleSuccess("可暂停任务正常执行完成");
    }
}
