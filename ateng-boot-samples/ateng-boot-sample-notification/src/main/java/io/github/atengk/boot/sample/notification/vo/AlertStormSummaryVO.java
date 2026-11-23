package io.github.atengk.boot.sample.notification.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 告警风暴三阶抑制执行结果视图对象
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertStormSummaryVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 模拟瞬时并发调用总次数
     */
    private Integer totalAttempts;

    /**
     * 首发即时发送紧急通报次数
     */
    private Integer initialDispatched;

    /**
     * 窗口期内静默拦截原子计数次数
     */
    private Integer suppressedCount;

    /**
     * 异常计算指纹 MD5
     */
    private String fingerprint;

    /**
     * 抑制窗口秒数
     */
    private Long suppressWindowSeconds;

    /**
     * 状态说明
     */
    private String message;
}
