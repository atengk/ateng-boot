package io.github.atengk.boot.job.xxl.thread;

import com.xxl.job.core.handler.IJobHandler;
import com.xxl.job.core.openapi.executor.dto.TriggerRequest;
import com.xxl.job.core.thread.JobThread;
import com.xxl.tool.response.Response;
import io.github.atengk.boot.job.xxl.timeout.AdminTimeoutRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 生产受管 XXL-Job 执行线程
 *
 * <p>继承官方 {@link JobThread}，在接收到调度触发参数时，若检测到 Admin 下发了任务超时阈值（{@code executorTimeout > 0}），
 * 自动将该超时转存至 {@link AdminTimeoutRegistry} 交由本地受管看门狗治理，并将请求中的 {@code executorTimeout} 重置为 0，
 * 从根源上消除官方底层频繁创建 {@code new Thread(futureTask)} 裸野线程的缺陷。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class AtengJobThread extends JobThread {

    private static final Logger log = LoggerFactory.getLogger(AtengJobThread.class);

    public AtengJobThread(int jobId, IJobHandler handler) {
        super(jobId, handler);
    }

    @Override
    public Response<String> pushTriggerQueue(TriggerRequest triggerParam) {
        if (triggerParam != null && triggerParam.getExecutorTimeout() > 0) {
            log.debug("捕获到 XXL-Job 调度中心下发超时配置 [jobId: {}, logId: {}, timeout: {}s]，已转交受管本地看门狗治理并规避野线程创建",
                    triggerParam.getJobId(), triggerParam.getLogId(), triggerParam.getExecutorTimeout());
            AdminTimeoutRegistry.register(triggerParam.getLogId(), triggerParam.getExecutorTimeout());
            triggerParam.setExecutorTimeout(0);
        }
        return super.pushTriggerQueue(triggerParam);
    }

}
