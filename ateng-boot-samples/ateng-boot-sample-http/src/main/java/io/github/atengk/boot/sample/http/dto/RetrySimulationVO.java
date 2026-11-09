package io.github.atengk.boot.sample.http.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 瞬态故障指数退避重试演练结果视图对象
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrySimulationVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 最终请求状态 (如 RECOVERED)
     */
    private String status;

    /**
     * 触发重试并成功恢复的总尝试次数
     */
    private int totalAttempts;

    /**
     * 演练说明
     */
    private String message;
}
