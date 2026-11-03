package io.github.atengk.boot.liteflow.rule.event;

import org.springframework.context.ApplicationEvent;

import java.util.Objects;

/**
 * LiteFlow 规则动态热更新 Spring 领域事件
 * 用于在分布式或单体应用中接收规则变更通知（如后台保存、MQ 广播、配置中心监听），
 * 触发 LiteFlow 规则链的即时平滑热生效
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class LiteflowRuleRefreshEvent extends ApplicationEvent {

    /**
     * 规则热重载类型
     */
    public enum RefreshType {
        /**
         * 全量刷新：从所有注册的规则源拉取全量规则，并重载静态规则配置
         */
        ALL,

        /**
         * 单链刷新：指定 chainId 进行单条规则链热重载（可携带新 el 表达式或从规则源重新查询）
         */
        CHAIN,

        /**
         * 原始文本热载：直接解析 XML/JSON/YML 格式的全量或增量文本
         */
        RAW_CONTENT
    }

    private final RefreshType refreshType;
    private final String chainId;
    private final String el;
    private final String ruleContent;

    public LiteflowRuleRefreshEvent(Object source, RefreshType refreshType, String chainId, String el, String ruleContent) {
        super(source);
        this.refreshType = Objects.requireNonNull(refreshType, "refreshType 不能为空");
        this.chainId = chainId;
        this.el = el;
        this.ruleContent = ruleContent;
    }

    /**
     * 创建全量规则刷新事件
     *
     * @param source 事件发布源
     * @return 刷新事件
     */
    public static LiteflowRuleRefreshEvent refreshAll(Object source) {
        return new LiteflowRuleRefreshEvent(source, RefreshType.ALL, null, null, null);
    }

    /**
     * 创建单条规则链按 ID 刷新事件（从规则源重新拉取最新定义）
     *
     * @param source  事件发布源
     * @param chainId 目标规则链 ID
     * @return 刷新事件
     */
    public static LiteflowRuleRefreshEvent refreshChain(Object source, String chainId) {
        if (chainId == null || chainId.isBlank()) {
            throw new IllegalArgumentException("chainId 不能为空");
        }
        return new LiteflowRuleRefreshEvent(source, RefreshType.CHAIN, chainId, null, null);
    }

    /**
     * 创建单条规则链显式 EL 刷新事件（直接以此 EL 覆盖生效）
     *
     * @param source  事件发布源
     * @param chainId 目标规则链 ID
     * @param el      新的 EL 表达式
     * @return 刷新事件
     */
    public static LiteflowRuleRefreshEvent refreshChain(Object source, String chainId, String el) {
        if (chainId == null || chainId.isBlank()) {
            throw new IllegalArgumentException("chainId 不能为空");
        }
        if (el == null || el.isBlank()) {
            throw new IllegalArgumentException("el 表达式不能为空");
        }
        return new LiteflowRuleRefreshEvent(source, RefreshType.CHAIN, chainId, el, null);
    }

    /**
     * 创建原始规则文本内容热重载事件
     *
     * @param source      事件发布源
     * @param ruleContent 规则文本内容 (XML/JSON/YML)
     * @return 刷新事件
     */
    public static LiteflowRuleRefreshEvent refreshContent(Object source, String ruleContent) {
        if (ruleContent == null || ruleContent.isBlank()) {
            throw new IllegalArgumentException("ruleContent 不能为空");
        }
        return new LiteflowRuleRefreshEvent(source, RefreshType.RAW_CONTENT, null, null, ruleContent);
    }

    public RefreshType getRefreshType() {
        return refreshType;
    }

    public String getChainId() {
        return chainId;
    }

    public String getEl() {
        return el;
    }

    public String getRuleContent() {
        return ruleContent;
    }

    @Override
    public String toString() {
        return "LiteflowRuleRefreshEvent{" +
                "refreshType=" + refreshType +
                ", chainId='" + chainId + '\'' +
                ", el='" + el + '\'' +
                ", ruleContentLength=" + (ruleContent != null ? ruleContent.length() : 0) +
                '}';
    }
}
