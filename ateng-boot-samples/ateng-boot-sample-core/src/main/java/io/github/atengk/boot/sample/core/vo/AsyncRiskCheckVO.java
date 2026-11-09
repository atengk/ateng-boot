package io.github.atengk.boot.sample.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 异步风控并行短路预检响应视图对象
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AsyncRiskCheckVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 是否全部维度均通过校验
     */
    private boolean allPassed;

    /**
     * 预检总任务数
     */
    private int totalRules;

    /**
     * 执行耗时（毫秒）
     */
    private long executionTimeMs;
}
