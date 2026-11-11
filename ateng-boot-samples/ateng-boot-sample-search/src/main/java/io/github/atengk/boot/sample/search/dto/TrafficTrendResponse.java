package io.github.atengk.boot.sample.search.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 时序流量直方图聚合走势响应 VO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrafficTrendResponse {

    /**
     * 时间分桶标识（如 2026-11-02）
     */
    private String bucket;

    /**
     * 该时间桶内访问量
     */
    private Long docCount;
}
