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
}
