package io.github.atengk.boot.job.xxl.executor;

import com.xxl.job.core.executor.XxlJobExecutor;
import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import com.xxl.job.core.handler.IJobHandler;
import com.xxl.job.core.thread.JobThread;
import io.github.atengk.boot.job.xxl.thread.AtengJobThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentMap;

/**
 * XXL-Job 生产级受管 Spring 执行器
 *
 * <p>继承 {@link XxlJobSpringExecutor}，重写线程注册逻辑以注入 {@link AtengJobThread}，
 * 在任务派发期无感拦截 Admin 下发的超时参数并转存至本地看门狗，彻底终结官方底层裸创建 {@code new Thread(futureTask)} 的缺陷。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class AtengXxlJobSpringExecutor extends XxlJobSpringExecutor {

    private static final Logger log = LoggerFactory.getLogger(AtengXxlJobSpringExecutor.class);

    private static final Field JOB_THREAD_REPOSITORY_FIELD;

    static {
        Field field = ReflectionUtils.findField(XxlJobExecutor.class, "jobThreadRepository");
        if (field != null) {
            ReflectionUtils.makeAccessible(field);
        }
        JOB_THREAD_REPOSITORY_FIELD = field;
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

}
