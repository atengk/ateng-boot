package io.github.atengk.boot.liteflow.rule;

import java.io.Serializable;
import java.util.Objects;

/**
 * LiteFlow 动态规则元数据定义模型
 * 承载流程链标识、EL 编排表达式、规则状态及路由元数据，解耦底层持久化存储介质
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class RuleSourceDefinition implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 规则流程链唯一标识 (chainId)
     */
    private String chainId;

    /**
     * 规则显示名称/中文描述
     */
    private String ruleName;

    /**
     * LiteFlow 规则编排 EL 表达式
     */
    private String el;

    /**
     * 规则启用状态（true-启用，false-停用/下线）
     */
    private boolean enabled = true;

    /**
     * 规则路由/业务域/多租户分组标识
     */
    private String routeGroup = "DEFAULT";

    /**
     * 规则所属命名空间（默认为空，缺省时继承 routeGroup）
     */
    private String namespace;

    /**
     * 决策路由条件 EL 表达式（可选，用于构建决策路由表 Route Chain）
     */
    private String routeEl;

    /**
     * 规则详细描述说明
     */
    private String description;

    public RuleSourceDefinition() {
    }

    public RuleSourceDefinition(String chainId, String el) {
        this.chainId = chainId;
        this.el = el;
    }

    public RuleSourceDefinition(String chainId, String el, String ruleName, boolean enabled) {
        this.chainId = chainId;
        this.el = el;
        this.ruleName = ruleName;
        this.enabled = enabled;
    }

    public RuleSourceDefinition(String chainId, String ruleName, String el, boolean enabled, String routeGroup, String description) {
        this.chainId = chainId;
        this.ruleName = ruleName;
        this.el = el;
        this.enabled = enabled;
        this.routeGroup = routeGroup;
        this.description = description;
    }

    public RuleSourceDefinition(String chainId, String ruleName, String el, boolean enabled, String routeGroup, String description, String routeEl) {
        this.chainId = chainId;
        this.ruleName = ruleName;
        this.el = el;
        this.enabled = enabled;
        this.routeGroup = routeGroup;
        this.description = description;
        this.routeEl = routeEl;
    }

    public String getChainId() {
        return chainId;
    }

    public void setChainId(String chainId) {
        this.chainId = chainId;
    }

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public String getEl() {
        return el;
    }

    public void setEl(String el) {
        this.el = el;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getRouteGroup() {
        return routeGroup;
    }

    public void setRouteGroup(String routeGroup) {
        this.routeGroup = routeGroup;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getRouteEl() {
        return routeEl;
    }

    public void setRouteEl(String routeEl) {
        this.routeEl = routeEl;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RuleSourceDefinition that = (RuleSourceDefinition) o;
        return Objects.equals(chainId, that.chainId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(chainId);
    }

    @Override
    public String toString() {
        return "RuleSourceDefinition{" +
                "chainId='" + chainId + '\'' +
                ", ruleName='" + ruleName + '\'' +
                ", el='" + el + '\'' +
                ", enabled=" + enabled +
                ", routeGroup='" + routeGroup + '\'' +
                ", namespace='" + namespace + '\'' +
                ", routeEl='" + routeEl + '\'' +
                ", description='" + description + '\'' +
                '}';
    }
}
