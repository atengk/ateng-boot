package io.github.atengk.boot.openapi.guard;

import java.util.Collection;

/**
 * OpenAPI 原生 CIDR 子网与 IP 地址匹配器
 * <p>
 * 已统一下沉收敛至 {@link io.github.atengk.boot.core.util.CidrMatcher}。
 * 本类作为向后兼容层保留，完全继承 Core 底座能力。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-30
 * @see io.github.atengk.boot.core.util.CidrMatcher
 */
public class OpenApiCidrMatcher extends io.github.atengk.boot.core.util.CidrMatcher {

    /**
     * 基于规则列表构造匹配器实例
     *
     * @param patterns IP 或 CIDR 网段规则集合
     */
    public OpenApiCidrMatcher(Collection<String> patterns) {
        super(patterns);
    }
}
