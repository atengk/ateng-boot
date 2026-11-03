package io.github.atengk.boot.liteflow.rule;

import org.springframework.core.Ordered;

import java.util.List;

/**
 * LiteFlow 多源动态规则加载提供者扩展 SPI
 * 允许开发者或模块扩展自定义规则存储源（如数据库、配置中心 Nacos/Apollo、Git 仓库或远程 HTTP），
 * 规则管理器将聚合所有 SPI 实现并实现容器启动自动预热与事件驱动热重载
 *
 * @author Ateng
 * @since 2026-11-03
 */
public interface LiteflowRuleSourceProvider extends Ordered {

    /**
     * 加载全量规则定义列表
     *
     * @return 规则定义列表；无数据时返回空集合，严禁返回 null
     */
    List<RuleSourceDefinition> loadRules();

    /**
     * 根据流程链 ID 单独拉取规则定义（支持精准查询）
     * 默认从全量列表中检索，实现类可根据介质特性重写以提供更高性能的单条查询
     *
     * @param chainId 流程链 ID
     * @return 规则定义，若未检索到则返回 null
     */
    default RuleSourceDefinition loadRule(String chainId) {
        if (chainId == null || chainId.isBlank()) {
            return null;
        }
        List<RuleSourceDefinition> rules = loadRules();
        if (rules == null || rules.isEmpty()) {
            return null;
        }
        return rules.stream()
                .filter(r -> chainId.equals(r.getChainId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 规则源名称（用于监控、审计与日志记录）
     *
     * @return 规则源名称
     */
    default String getName() {
        return getClass().getSimpleName();
    }

    /**
     * 规则源优先级（数值越小优先级越高，同名 chainId 优先采纳高优先级规则源）
     *
     * @return 排序数值
     */
    @Override
    default int getOrder() {
        return 0;
    }
}
