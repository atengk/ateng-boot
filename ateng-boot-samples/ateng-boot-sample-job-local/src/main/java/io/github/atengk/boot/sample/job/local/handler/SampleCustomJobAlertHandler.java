package io.github.atengk.boot.sample.job.local.handler;

import io.github.atengk.boot.job.local.alert.JobAlertContext;
import io.github.atengk.boot.job.local.alert.JobAlertHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 示例自定义告警处理器 SPI 实现，演示企业级告警定制扩展接管
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Component
public class SampleCustomJobAlertHandler implements JobAlertHandler {

    private static final Logger log = LoggerFactory.getLogger(SampleCustomJobAlertHandler.class);

    private final List<JobAlertContext> handledAlerts = new CopyOnWriteArrayList<>();

    @Override
    public void handleAlert(JobAlertContext context) {
        if (context == null) {
            return;
        }
        log.warn("SampleCustomJobAlertHandler: 捕获到任务告警 [jobName={}, alertType={}, errorMsg={}]",
                context.getJobName(), context.getAlertType(), context.getErrorMsg());
        handledAlerts.add(context);
    }

    public List<JobAlertContext> getHandledAlerts() {
        return handledAlerts;
    }

    public void clearAlerts() {
        handledAlerts.clear();
    }
}
