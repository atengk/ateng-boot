package io.github.atengk.boot.liteflow.component;

import com.yomahub.liteflow.core.NodeComponent;
import io.github.atengk.boot.core.util.JsonUtil;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * LiteFlow 组件泛型上下文与参数提取通用支撑类
 * <p>
 * 为泛型组件提供统一的强类型上下文解析（严格类型校验杜绝盲目类型转换）、组件标签与配置数据反序列化能力
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
public final class ComponentSupport {

    private ComponentSupport() {
    }

    /**
     * 解析并获取组件强类型上下文实例
     * <p>
     * 优先按目标 {@code contextClass} 获取执行槽中对应的 ContextBean；
     * 若未命中且存在首个上下文，显式校验其类型兼容性后再行返回；类型不兼容时拒绝盲转并抛出明确语义异常。
     * </p>
     *
     * @param component    当前节点组件
     * @param contextClass 期望绑定的上下文 Class
     * @param <T>          上下文类型
     * @return 匹配的上下文实例
     * @throws IllegalStateException 若未获取到或类型不匹配
     */
    @SuppressWarnings("unchecked")
    public static <T> T resolveContext(NodeComponent component, Class<T> contextClass) {
        if (component == null) {
            throw new IllegalArgumentException("component 不能为空");
        }
        try {
            if (contextClass != null) {
                try {
                    T ctx = component.getContextBean(contextClass);
                    if (ctx != null) {
                        return ctx;
                    }
                } catch (Throwable ignored) {
                    // 若按指定类型获取抛出 NoSuchContextBeanException，允许继续探测首个上下文
                }
            }
            Object firstCtx = null;
            try {
                firstCtx = component.getFirstContextBean();
            } catch (Throwable ignored) {
            }
            if (firstCtx != null) {
                if (contextClass == null || contextClass.isInstance(firstCtx)) {
                    return (T) firstCtx;
                }
                throw new IllegalStateException("当前执行槽首个上下文实例类型 [" + firstCtx.getClass().getName()
                        + "] 与期望类型 [" + contextClass.getName() + "] 不匹配，拒绝执行不安全的盲目类型转换");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable t) {
            String targetName = contextClass != null ? contextClass.getName() : "Context";
            throw new IllegalStateException("未能从当前执行槽中获取到类型为 [" + targetName + "] 的业务上下文实例: " + t.getMessage(), t);
        }
        String targetName = contextClass != null ? contextClass.getName() : "Context";
        throw new IllegalStateException("未能从当前执行槽中获取到类型为 [" + targetName + "] 的业务上下文实例，请检查流程执行入参");
    }

    /**
     * 获取当前组件节点的标签 Tag
     *
     * @param component 当前节点组件
     * @return 节点标签字符串，若无则返回 null
     */
    public static String getTag(NodeComponent component) {
        return (component != null && component.getRefNode() != null) ? component.getRefNode().getTag() : null;
    }

    /**
     * 将节点配置的 cmpData JSON 反序列化为强类型 Bean 或 Record
     *
     * @param component 当前节点组件
     * @param dataClass 目标配置数据 Class
     * @param <D>       配置数据泛型
     * @return 反序列化后的配置对象，若无配置数据则返回 null
     */
    public static <D> D getCmpData(NodeComponent component, Class<D> dataClass) {
        Objects.requireNonNull(dataClass, "dataClass 不能为空");
        String cmpData = (component != null && component.getRefNode() != null) ? component.getRefNode().getCmpData() : null;
        if (cmpData == null || cmpData.isBlank()) {
            return null;
        }
        if (String.class.equals(dataClass) || Object.class.equals(dataClass)) {
            @SuppressWarnings("unchecked")
            D raw = (D) cmpData;
            return raw;
        }
        return JsonUtil.parseObject(cmpData, dataClass);
    }

    /**
     * 将节点配置的 cmpData JSON 数组反序列化为强类型列表
     *
     * @param component    当前节点组件
     * @param elementClass 列表元素 Class
     * @param <D>          元素泛型
     * @return 反序列化后的列表，若无数据返回空列表
     */
    public static <D> List<D> getCmpDataList(NodeComponent component, Class<D> elementClass) {
        Objects.requireNonNull(elementClass, "elementClass 不能为空");
        String cmpData = (component != null && component.getRefNode() != null) ? component.getRefNode().getCmpData() : null;
        if (cmpData == null || cmpData.isBlank()) {
            return Collections.emptyList();
        }
        return JsonUtil.parseList(cmpData, elementClass);
    }

    /**
     * 获取流程初始强类型请求入参
     *
     * @param component  当前节点组件
     * @param paramClass 期望的入参 Class 类型
     * @param <P>        入参泛型
     * @return 强类型入参，若未传递则返回 null
     * @throws IllegalStateException 若实际入参类型与期望类型不匹配
     */
    public static <P> P getRequestData(NodeComponent component, Class<P> paramClass) {
        Objects.requireNonNull(paramClass, "paramClass 不能为空");
        if (component == null) {
            throw new IllegalArgumentException("component 不能为空");
        }
        Object raw = component.getRequestData();
        if (raw == null) {
            return null;
        }
        if (paramClass.isInstance(raw)) {
            return paramClass.cast(raw);
        }
        throw new IllegalStateException("流程入参类型 [" + raw.getClass().getName()
                + "] 与期望类型 [" + paramClass.getName() + "] 不匹配，拒绝执行不安全的盲目转换");
    }

    /**
     * 获取当前循环组件的迭代轮次下标 (从 0 开始)
     *
     * @param component 当前节点组件
     * @return 循环下标，若未在循环上下文执行则返回 null
     */
    public static Integer getLoopIndex(NodeComponent component) {
        return (component != null && component.getRefNode() != null) ? component.getRefNode().getLoopIndex() : null;
    }

    /**
     * 获取当前循环组件的强类型迭代对象
     *
     * @param component    当前节点组件
     * @param elementClass 期望的迭代元素 Class 类型
     * @param <E>          元素泛型
     * @return 强类型当前迭代元素，若无迭代对象则返回 null
     * @throws IllegalStateException 若当前迭代对象类型与期望类型不匹配
     */
    public static <E> E getCurrLoopObject(NodeComponent component, Class<E> elementClass) {
        Objects.requireNonNull(elementClass, "elementClass 不能为空");
        if (component == null) {
            throw new IllegalArgumentException("component 不能为空");
        }
        Object raw = component.getCurrLoopObj();
        if (raw == null) {
            return null;
        }
        if (elementClass.isInstance(raw)) {
            return elementClass.cast(raw);
        }
        throw new IllegalStateException("当前循环迭代对象类型 [" + raw.getClass().getName()
                + "] 与期望类型 [" + elementClass.getName() + "] 不匹配，拒绝执行不安全的盲目转换");
    }

    /**
     * 向目标下游节点进行点对点私有数据投递（阅后即焚，不污染全局上下文）
     *
     * @param component    当前节点组件
     * @param targetNodeId 目标节点 ID
     * @param data         私有传输数据对象
     */
    public static void deliverTo(NodeComponent component, String targetNodeId, Object data) {
        if (component == null) {
            throw new IllegalArgumentException("component 不能为空");
        }
        if (targetNodeId == null || targetNodeId.isBlank()) {
            throw new IllegalArgumentException("targetNodeId 不能为空");
        }
        component.sendPrivateDeliveryData(targetNodeId, data);
    }

    /**
     * 接收上游节点通过私有投递传递给当前节点的强类型私有数据
     *
     * @param component 当前节点组件
     * @param dataClass 期望的私有数据 Class 类型
     * @param <D>       数据泛型
     * @return 强类型私有数据，若无投递数据则返回 null
     * @throws IllegalStateException 若投递数据类型与期望类型不匹配
     */
    public static <D> D receiveDelivery(NodeComponent component, Class<D> dataClass) {
        Objects.requireNonNull(dataClass, "dataClass 不能为空");
        if (component == null) {
            throw new IllegalArgumentException("component 不能为空");
        }
        Object raw = component.getPrivateDeliveryData();
        if (raw == null) {
            return null;
        }
        if (dataClass.isInstance(raw)) {
            return dataClass.cast(raw);
        }
        throw new IllegalStateException("私有投递数据类型 [" + raw.getClass().getName()
                + "] 与期望类型 [" + dataClass.getName() + "] 不匹配，拒绝执行不安全的盲目转换");
    }
}
