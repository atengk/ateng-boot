package io.github.atengk.boot.sample.core.vo;

import io.github.atengk.boot.core.diagnostic.SlowBeanDiagnosticPostProcessor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 慢调用领域事件与启动慢 Bean 诊断闭环响应视图对象
 *
 * @author Ateng
 * @since 2026-11-14
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SlowAlertSampleVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 任务业务标识
     */
    private String taskId;

    /**
     * 慢方法实际执行耗时（毫秒）
     */
    private Long methodCostMillis;

    /**
     * 监听器累计捕获的 SlowAlertEvent 事件总数
     */
    private Integer eventConsumedCount;

    /**
     * SlowAlertEvent 记录的目标类全限定名
     */
    private String className;

    /**
     * SlowAlertEvent 记录的目标方法名
     */
    private String methodName;

    /**
     * SlowAlertEvent 记录的实际耗时（毫秒）
     */
    private Long elapsedMs;

    /**
     * SlowAlertEvent 记录的生效告警阈值（毫秒）
     */
    private Long thresholdMs;

    /**
     * SlowAlertEvent 记录的脱敏截断入参摘要
     */
    private String argsSummary;

    /**
     * SlowAlertEvent 携带的全链路追踪标识
     */
    private String traceId;

    /**
     * SlowAlertEvent 触发时间
     */
    private LocalDateTime occurredAt;

    /**
     * 容器中是否已自动装配 SlowBeanDiagnosticPostProcessor 慢 Bean 启动诊断器
     */
    private Boolean slowBeanDiagnosticEnabled;

    /**
     * 当前记录的启动期耗时超阈值慢 Bean 数量
     */
    private Integer recordedSlowBeanCount;

    /**
     * 启动期慢 Bean 诊断记录列表
     */
    @Builder.Default
    private List<SlowBeanDiagnosticPostProcessor.SlowBeanInfo> recordedSlowBeans = Collections.emptyList();
}
