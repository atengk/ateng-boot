package io.github.atengk.boot.sample.job.xxl.vo;

import io.github.atengk.boot.job.xxl.helper.XxlJobTestHelper.XxlJobTestResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 任务调度触发响应视图对象
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobTriggerVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 是否执行成功
     */
    private boolean success;

    /**
     * 响应状态码 (200 为成功，500 为失败)
     */
    private int handleCode;

    /**
     * 响应描述文案
     */
    private String handleMsg;

    /**
     * 执行耗时 (毫秒)
     */
    private long durationMs;

    /**
     * 执行异常说明 (若有)
     */
    private String errorMessage;

    /**
     * 从脱网诊断结果构建视图对象
     *
     * @param result 脱网执行诊断报告
     * @return 转换后的 VO 对象
     */
    public static JobTriggerVO from(XxlJobTestResult result) {
        if (result == null) {
            return null;
        }
        return JobTriggerVO.builder()
                .success(result.isSuccess())
                .handleCode(result.handleCode())
                .handleMsg(result.handleMsg())
                .durationMs(result.durationMs())
                .errorMessage(result.throwable() != null ? result.throwable().getMessage() : null)
                .build();
    }
}
