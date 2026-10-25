package io.github.atengk.boot.job.local.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 任务执行成果结构化摘要对象
 * <p>
 * 业务方法可选择返回此类实例，以向调度系统反馈本次批处理的成果与统计指标。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class JobResult {

    /**
     * 是否执行成功
     */
    private boolean success;

    /**
     * 业务成果说明文本（如 "成功同步 150 条用户数据"）
     */
    private String message;

    /**
     * 自定义统计或产物数据（可选）
     */
    private Object data;

    /**
     * 构造成功结果
     *
     * @param message 成功摘要
     * @return 成功对象
     */
    public static JobResult success(String message) {
        return JobResult.builder().success(true).message(message).build();
    }

    /**
     * 构造成功结果并挂载产物数据
     *
     * @param message 成功摘要
     * @param data    产物数据
     * @return 成功对象
     */
    public static JobResult success(String message, Object data) {
        return JobResult.builder().success(true).message(message).data(data).build();
    }

    /**
     * 构造失败结果
     *
     * @param message 失败说明
     * @return 失败对象
     */
    public static JobResult failure(String message) {
        return JobResult.builder().success(false).message(message).build();
    }
}
