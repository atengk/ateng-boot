package io.github.atengk.boot.sample.job.local.vo;

import io.github.atengk.boot.job.local.model.JobResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 任务执行结果视图对象
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobResultVO implements Serializable {

    /**
     * 是否执行成功
     */
    private boolean success;

    /**
     * 业务成果说明文本
     */
    private String message;

    /**
     * 产物载荷数据（可选）
     */
    private Object data;

    /**
     * 从内核模型转换为视图对象
     *
     * @param result 内核 JobResult 对象
     * @return 转换后的 VO
     */
    public static JobResultVO from(JobResult result) {
        if (result == null) {
            return null;
        }
        return JobResultVO.builder()
                .success(result.isSuccess())
                .message(result.getMessage())
                .data(result.getData())
                .build();
    }
}
