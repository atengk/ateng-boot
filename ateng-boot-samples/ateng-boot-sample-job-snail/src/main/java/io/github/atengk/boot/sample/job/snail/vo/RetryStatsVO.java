package io.github.atengk.boot.sample.job.snail.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 分布式协同重试场景统计视图模型
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetryStatsVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 重试场景标识
     */
    private String scene;

    /**
     * 累计尝试执行次数
     */
    private Integer attemptCount;
}
