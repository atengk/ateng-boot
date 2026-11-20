package io.github.atengk.boot.search.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.Map;

/**
 * 强类型分位数聚合结果模型（例如 P50 / P95 / P99）
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchPercentilesResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 分位点与对应指标值的映射（Key 如 "50.0"、"95.0"、"99.0"）
     */
    @Builder.Default
    private Map<String, Double> values = Collections.emptyMap();

    /**
     * 获取分位点映射字典（保底返回空 Map）
     *
     * @return 分位点数值字典
     */
    public Map<String, Double> getValues() {
        return values != null ? values : Collections.emptyMap();
    }

    /**
     * 按分位点百分比读取对应数值
     *
     * @param percent 分位点百分比（例如 50、95、99 或 99.9）
     * @return 对应分位数值（不存在时返回 null）
     */
    public Double getPercentile(double percent) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        String canonicalKey = Double.toString(percent);
        if (values.containsKey(canonicalKey)) {
            return values.get(canonicalKey);
        }
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            try {
                if (Math.abs(Double.parseDouble(entry.getKey()) - percent) < 1e-6) {
                    return entry.getValue();
                }
            } catch (NumberFormatException ignored) {
                // 非数值分位键安全跳过
            }
        }
        return null;
    }
}
