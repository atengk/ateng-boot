package io.github.atengk.boot.sample.job.xxl.handler;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import io.github.atengk.boot.job.xxl.annotation.AtengXxlJob;
import io.github.atengk.boot.sample.job.xxl.dto.UserSyncParam;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 强类型 DTO 入参绑定与 JSR-303 格式校验任务样例
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Component
public class ParamValidationJobHandler {

    private static final Logger log = LoggerFactory.getLogger(ParamValidationJobHandler.class);

    /**
     * 强类型参数同步任务：方法形参声明 @Valid，切面自动反序列化 JSON 并执行 JSR-303 防卫
     *
     * @param param 经过校验的强类型参数
     */
    @XxlJob("userSyncJob")
    @AtengXxlJob(value = "userSyncJob")
    public void executeUserSync(@Valid UserSyncParam param) {
        log.info(">>>>>> [ParamValidationJobHandler] 开始执行用户同步任务, 批次: {}, 批大小: {}, 租户: {}",
                param.getBatchNo(), param.getBatchSize(), param.getTenantId());

        XxlJobHelper.log("用户同步批次 [{}] 校验通过，正在处理 {} 条数据...", param.getBatchNo(), param.getBatchSize());
        XxlJobHelper.handleSuccess("同步任务成功完成, 处理批大小: " + param.getBatchSize());
    }
}
