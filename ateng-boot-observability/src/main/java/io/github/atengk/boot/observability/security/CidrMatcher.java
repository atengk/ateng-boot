package io.github.atengk.boot.observability.security;

import java.util.Collection;

/**
 * 纯 JDK 17 原生 CIDR 子网与 IP 地址匹配器
 * <p>
 * 已统一下沉收敛至 {@link io.github.atengk.boot.core.util.CidrMatcher}。
 * 本类作为向后兼容层保留，完全继承 Core 底座能力。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-30
 * @see io.github.atengk.boot.core.util.CidrMatcher
 */
public class CidrMatcher extends io.github.atengk.boot.core.util.CidrMatcher {

    /**
     * 基于规则列表构造匹配器实例
     *
     * @param patterns IP 或 CIDR 网段规则集合
     */
    public CidrMatcher(Collection<String> patterns) {
        super(patterns);
    }
}
