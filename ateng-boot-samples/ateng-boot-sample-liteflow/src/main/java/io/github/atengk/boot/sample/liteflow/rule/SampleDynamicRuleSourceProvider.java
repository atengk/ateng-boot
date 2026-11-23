package io.github.atengk.boot.sample.liteflow.rule;

import io.github.atengk.boot.liteflow.rule.LiteflowRuleSourceProvider;
import io.github.atengk.boot.liteflow.rule.RuleSourceDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 示例多源动态规则提供者（纯内存业务规则仓储）
 * 模拟中后台规则中心存储并配合 {@code LiteflowRuleSourceManager} 演示动态纳管规则刷新与孤儿链自动下线
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Component
public class SampleDynamicRuleSourceProvider implements LiteflowRuleSourceProvider {

    public static final String DEFAULT_MANAGED_CHAIN_ID = "dynamic-vip-discount-rule";
    public static final String DEFAULT_MANAGED_CHAIN_EL = "THEN(dynamicValidateCmp, dynamicTransformCmp);";

    private final Map<String, RuleSourceDefinition> rules = new ConcurrentHashMap<>();

    public SampleDynamicRuleSourceProvider() {
        upsertRule(DEFAULT_MANAGED_CHAIN_ID, DEFAULT_MANAGED_CHAIN_EL);
    }

    @Override
    public String getName() {
        return "sample-in-memory-rule-source";
    }

    @Override
    public List<RuleSourceDefinition> loadRules() {
        return new ArrayList<>(rules.values());
    }

    @Override
    public RuleSourceDefinition loadRule(String chainId) {
        if (chainId == null || chainId.isBlank()) {
            return null;
        }
        return rules.get(chainId);
    }

    /**
     * 新增或更新内存规则仓储中的动态规则定义
     *
     * @param chainId 规则链标识
     * @param el      规则主体 EL 表达式
     */
    public void upsertRule(String chainId, String el) {
        if (chainId == null || chainId.isBlank()) {
            throw new IllegalArgumentException("chainId 不能为空");
        }
        if (el == null || el.isBlank()) {
            throw new IllegalArgumentException("el 不能为空");
        }
        RuleSourceDefinition definition = new RuleSourceDefinition(chainId, el, chainId, true);
        definition.setDescription("Sample 动态运营规则: " + chainId);
        rules.put(chainId, definition);
    }

    /**
     * 从内存规则仓储中物理移除指定规则定义，供下次全量刷新验证孤儿链自动下线
     *
     * @param chainId 规则链标识
     * @return 是否存在并被移除
     */
    public boolean removeRule(String chainId) {
        if (chainId == null || chainId.isBlank()) {
            return false;
        }
        return rules.remove(chainId) != null;
    }
}
