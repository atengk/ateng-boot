package io.github.atengk.boot.sample.job.local.job;

import io.github.atengk.boot.job.local.annotation.LocalJob;
import io.github.atengk.boot.job.local.model.JobContext;
import io.github.atengk.boot.job.local.model.JobResult;
import io.github.atengk.boot.sample.job.local.dto.ArchivingJobParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 历史数据冷归档与压缩清理业务场景任务
 * <p>
 * 演示特性：强类型业务 DTO 动态反序列化注入、JobContext 组合入参与 JSR-303 Fail-Fast 校验阻断。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-01
 */
@Component
public class TypedParamArchivingScenarioJob {

    private static final Logger log = LoggerFactory.getLogger(TypedParamArchivingScenarioJob.class);

    private volatile ArchivingJobParam lastReceivedParam;

    /**
     * 执行指定业务表的数据冷归档
     *
     * @param param   强类型业务入参（自动进行 JSR-303 格式校验）
     * @param context 任务上下文
     * @return 归档处理结果
     */
    @LocalJob(
            name = "typedParamArchivingJob",
            description = "历史数据冷归档与压缩清理任务",
            fixedDelay = "1h",
            initialDelay = "1h"
    )
    public JobResult executeArchiving(ArchivingJobParam param, JobContext context) {
        this.lastReceivedParam = param;
        context.log("开始归档业务表 [" + param.getTableName() + "], 保留天数: " + param.getRetentionDays()
                + ", 单批次容量: " + param.getBatchSize() + ", dryRun=" + param.isDryRun());
        log.info("Archiving executed for table={}, dryRun={}, traceId={}",
                param.getTableName(), param.isDryRun(), context.getTraceId());
        return JobResult.success("表 [" + param.getTableName() + "] 历史记录归档完成");
    }

    /**
     * 获取最近一次成功绑定的入参 DTO
     *
     * @return 业务入参 DTO
     */
    public ArchivingJobParam getLastReceivedParam() {
        return lastReceivedParam;
    }
}
