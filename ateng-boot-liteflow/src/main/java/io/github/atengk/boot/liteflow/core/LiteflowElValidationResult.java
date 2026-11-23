package io.github.atengk.boot.liteflow.core;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * LiteFlow EL 规则表达式语法与组件引用结构化校验结果
 *
 * @author Ateng
 * @since 2026-11-06
 */
public class LiteflowElValidationResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 表达式语法与组件引用是否全部合法
     */
    private final boolean valid;

    /**
     * 被校验的原始 EL 表达式
     */
    private final String el;

    /**
     * 校验失败诊断信息（校验通过时为 null）
     */
    private final String errorMessage;

    /**
     * 是否存在未在容器中注册的缺失组件或子流程链
     */
    private final boolean componentMissing;

    /**
     * 表达式中引用的已注册组件节点 ID 集合
     */
    private final Set<String> referencedNodeIds;

    /**
     * 表达式中引用的已注册子流程链 ID 集合
     */
    private final Set<String> referencedChainIds;

    /**
     * 表达式中引用但未注册的缺失组件或子流程链 ID 清单
     */
    private final Set<String> missingComponentIds;

    public LiteflowElValidationResult(boolean valid, String el, String errorMessage) {
        this(valid, el, errorMessage, false, Collections.emptySet(), Collections.emptySet(), Collections.emptySet());
    }

    public LiteflowElValidationResult(boolean valid,
                                      String el,
                                      String errorMessage,
                                      boolean componentMissing,
                                      Set<String> referencedNodeIds,
                                      Set<String> referencedChainIds,
                                      Set<String> missingComponentIds) {
        this.valid = valid;
        this.el = el;
        this.errorMessage = errorMessage;
        this.referencedNodeIds = toUnmodifiableSet(referencedNodeIds);
        this.referencedChainIds = toUnmodifiableSet(referencedChainIds);
        this.missingComponentIds = toUnmodifiableSet(missingComponentIds);
        this.componentMissing = componentMissing || !this.missingComponentIds.isEmpty();
    }

    /**
     * 构建校验成功结果
     *
     * @param el 原始 EL 表达式
     * @return 成功结果实例
     */
    public static LiteflowElValidationResult success(String el) {
        return new LiteflowElValidationResult(true, el, null);
    }

    /**
     * 构建包含解析引用拓扑的校验成功结果
     *
     * @param el                 原始 EL 表达式
     * @param referencedNodeIds  已注册组件节点 ID 集合
     * @param referencedChainIds 已注册子流程链 ID 集合
     * @return 成功结果实例
     */
    public static LiteflowElValidationResult success(String el,
                                                     Set<String> referencedNodeIds,
                                                     Set<String> referencedChainIds) {
        return new LiteflowElValidationResult(true, el, null, false,
                referencedNodeIds, referencedChainIds, Collections.emptySet());
    }

    /**
     * 构建校验失败结果
     *
     * @param el           原始 EL 表达式
     * @param errorMessage 错误原因
     * @return 失败结果实例
     */
    public static LiteflowElValidationResult fail(String el, String errorMessage) {
        return new LiteflowElValidationResult(false, el, errorMessage);
    }

    /**
     * 构建包含引用诊断信息的校验失败结果
     *
     * @param el                  原始 EL 表达式
     * @param errorMessage        错误诊断原因
     * @param referencedNodeIds   已注册组件节点 ID 集合
     * @param referencedChainIds  已注册子流程链 ID 集合
     * @param missingComponentIds 未注册缺失组件/子链清单
     * @return 失败结果实例
     */
    public static LiteflowElValidationResult fail(String el,
                                                  String errorMessage,
                                                  Set<String> referencedNodeIds,
                                                  Set<String> referencedChainIds,
                                                  Set<String> missingComponentIds) {
        boolean missing = missingComponentIds != null && !missingComponentIds.isEmpty();
        return new LiteflowElValidationResult(false, el, errorMessage, missing,
                referencedNodeIds, referencedChainIds, missingComponentIds);
    }

    /**
     * 获取语法与引用是否全部合法
     *
     * @return true 表示合法有效，false 表示存在语法或缺失引用错误
     */
    public boolean isValid() {
        return valid;
    }

    /**
     * 获取被校验的原始 EL 表达式
     *
     * @return 原始 EL 表达式字符串
     */
    public String getEl() {
        return el;
    }

    /**
     * 获取校验失败诊断信息
     *
     * @return 错误原因；校验成功时返回 null
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * 判断是否存在未注册的缺失组件或子流程链
     *
     * @return true 表示存在缺失引用
     */
    public boolean isComponentMissing() {
        return componentMissing;
    }

    /**
     * 获取表达式中引用的已注册组件节点 ID 集合
     *
     * @return 不可变节点 ID 集合（无则为空集合）
     */
    public Set<String> getReferencedNodeIds() {
        return referencedNodeIds;
    }

    /**
     * 获取表达式中引用的已注册子流程链 ID 集合
     *
     * @return 不可变子链 ID 集合（无则为空集合）
     */
    public Set<String> getReferencedChainIds() {
        return referencedChainIds;
    }

    /**
     * 获取表达式中引用但未注册的缺失组件或子流程链清单
     *
     * @return 不可变缺失标识集合（无则为空集合）
     */
    public Set<String> getMissingComponentIds() {
        return missingComponentIds;
    }

    private static Set<String> toUnmodifiableSet(Set<String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(source));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LiteflowElValidationResult that = (LiteflowElValidationResult) o;
        return valid == that.valid
                && componentMissing == that.componentMissing
                && Objects.equals(el, that.el)
                && Objects.equals(errorMessage, that.errorMessage)
                && Objects.equals(referencedNodeIds, that.referencedNodeIds)
                && Objects.equals(referencedChainIds, that.referencedChainIds)
                && Objects.equals(missingComponentIds, that.missingComponentIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(valid, el, errorMessage, componentMissing,
                referencedNodeIds, referencedChainIds, missingComponentIds);
    }

    @Override
    public String toString() {
        return "LiteflowElValidationResult{" +
                "valid=" + valid +
                ", el='" + el + '\'' +
                ", errorMessage='" + errorMessage + '\'' +
                ", componentMissing=" + componentMissing +
                ", referencedNodeIds=" + referencedNodeIds +
                ", referencedChainIds=" + referencedChainIds +
                ", missingComponentIds=" + missingComponentIds +
                '}';
    }
}
