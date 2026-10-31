package io.github.atengk.boot.sample.job.xxl.handler;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import io.github.atengk.boot.job.xxl.annotation.AtengXxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 告警风暴抑制与故障自愈恢复演练任务样例
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Component
public class AlertDemoJobHandler {

    private static final Logger log = LoggerFactory.getLogger(AlertDemoJobHandler.class);

    private final AtomicBoolean faultInjected = new AtomicBoolean(false);

    /**
     * 设置是否注入故障
     */
    public void setFaultInjected(boolean fault) {
        this.faultInjected.set(fault);
    }

    /**
     * 告警与自愈任务：根据状态模拟失败抛错或成功恢复
     */
    @XxlJob("alertDemoJob")
    @AtengXxlJob(value = "alertDemoJob")
    public void executeAlertDemo() {
        if (faultInjected.get()) {
            log.error(">>>>>> [AlertDemoJobHandler] 模拟下游数据库超时故障！");
            throw new RuntimeException("下游数据库连接超时，模拟任务调度失败！");
        }
        log.info(">>>>>> [AlertDemoJobHandler] 任务执行正常。");
        XxlJobHelper.handleSuccess("任务执行成功，健康运行");
    }
}
