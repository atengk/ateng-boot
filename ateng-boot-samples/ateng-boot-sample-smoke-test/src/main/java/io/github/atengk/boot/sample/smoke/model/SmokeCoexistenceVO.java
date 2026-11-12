package io.github.atengk.boot.sample.smoke.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * 多 Starter 零冲突共存拓扑探针 VO
 *
 * @author Ateng
 * @since 2026-11-12
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SmokeCoexistenceVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 全家桶核心基础包（6 大 Starter）是否装配就绪
     */
    private boolean coreBundleReady;

    /**
     * 共存装配组件状态映射
     */
    private Map<String, Boolean> assembledComponents;
}
