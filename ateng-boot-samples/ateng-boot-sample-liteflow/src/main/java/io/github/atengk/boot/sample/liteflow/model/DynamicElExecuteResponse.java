package io.github.atengk.boot.sample.liteflow.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * 即时 EL 表达式执行结果
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DynamicElExecuteResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 执行是否成功
     */
    private boolean success;

    /**
     * 规则链执行耗时（毫秒）
     */
    private Long executeTimeMs;

    /**
     * 上下文变量与输出数据
     */
    private Map<String, Object> outputData;

    /**
     * 规则执行轨迹摘要
     */
    private String traceSummary;
}
