package io.github.atengk.boot.sample.job.snail.handler;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.core.util.ValidatorUtil;
import io.github.atengk.boot.sample.job.snail.dto.UserSyncParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 强类型入参校验调度任务执行器示例
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Slf4j
@Component
public class ParamValidationJobHandler {

    /**
     * 用户同步任务：强校验批次与分页大小入参
     */
    @JobExecutor(name = "userSyncJob")
    public ExecuteResult userSyncJob(JobArgs args) {
        Object rawParams = args != null ? args.getJobParams() : null;
        log.info("[ParamValidationJobHandler] 开始执行用户同步调度任务, 原始参数: {}", rawParams);

        UserSyncParam param;
        if (rawParams instanceof UserSyncParam userSyncParam) {
            param = userSyncParam;
        } else if (rawParams instanceof String json && StringUtils.hasText(json)) {
            param = JsonUtil.parseObject(json, UserSyncParam.class);
        } else {
            return ExecuteResult.failure("任务参数缺失或格式不合法");
        }

        // 第二道防线：基于 ValidatorUtil 编程式强校验业务不变量
        ValidatorUtil.validate(param);

        log.info("[ParamValidationJobHandler] 参数校验通过: batchNo={}, batchSize={}, dept={}",
                param.getBatchNo(), param.getBatchSize(), param.getDepartmentId());

        return ExecuteResult.success("Sync completed for batch " + param.getBatchNo());
    }
}
