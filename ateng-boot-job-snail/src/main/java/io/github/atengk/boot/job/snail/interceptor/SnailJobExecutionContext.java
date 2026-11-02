package io.github.atengk.boot.job.snail.interceptor;

import io.github.atengk.boot.job.snail.watchdog.SnailJobExecutionMetadata;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SnailJob 调度与切片执行拦截器上下文模型
 *
 * <p>封装被拦截任务的目标 Bean、执行方法、解析入参、执行元数据及阶段共享属性，
 * 贯穿 preHandle、postHandle 与 afterCompletion 三阶生命周期。</p>
 *
 * @author Ateng
 * @since 2026-11-02
 */
public class SnailJobExecutionContext {

    private final Object targetBean;
    private final Method method;
    private final Object[] args;
    private final SnailJobExecutionMetadata metadata;
    private final Map<String, Object> attributes;

    public SnailJobExecutionContext(Object targetBean, Method method, Object[] args, SnailJobExecutionMetadata metadata) {
        this.targetBean = targetBean;
        this.method = method;
        this.args = args != null ? args.clone() : new Object[0];
        this.metadata = metadata;
        this.attributes = new ConcurrentHashMap<>();
    }

    public Object getTargetBean() {
        return targetBean;
    }

    public Method getMethod() {
        return method;
    }

    public Object[] getArgs() {
        return args != null ? args.clone() : new Object[0];
    }

    public SnailJobExecutionMetadata getMetadata() {
        return metadata;
    }

    /**
     * 设置动态阶段共享属性
     *
     * @param name  属性键
     * @param value 属性值（为 null 时移除该属性）
     */
    public void setAttribute(String name, Object value) {
        Objects.requireNonNull(name, "attribute name 不能为 null");
        if (value != null) {
            attributes.put(name, value);
        } else {
            attributes.remove(name);
        }
    }

    /**
     * 获取指定名称的动态属性值
     *
     * @param name 属性键
     * @return 属性值，不存在时返回 null
     */
    public Object getAttribute(String name) {
        return name != null ? attributes.get(name) : null;
    }

    /**
     * 获取强类型动态属性值
     *
     * @param name         属性键
     * @param requiredType 期望类型
     * @param <T>          类型泛型
     * @return 转换后的属性值，不存在时返回 null
     * @throws IllegalArgumentException 当属性值无法转换为指定类型时抛出
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String name, Class<T> requiredType) {
        Object val = getAttribute(name);
        if (val == null) {
            return null;
        }
        if (requiredType != null && !requiredType.isInstance(val)) {
            throw new IllegalArgumentException("属性 [" + name + "] 类型为 " + val.getClass().getName() + "，无法转换为目标类型 " + requiredType.getName());
        }
        return (T) val;
    }

    /**
     * 判断是否包含指定动态属性
     *
     * @param name 属性键
     * @return true 若存在
     */
    public boolean hasAttribute(String name) {
        return name != null && attributes.containsKey(name);
    }

    /**
     * 移除指定动态属性
     *
     * @param name 属性键
     * @return 被移除的旧值，不存在时返回 null
     */
    public Object removeAttribute(String name) {
        return name != null ? attributes.remove(name) : null;
    }

    /**
     * 获取所有动态属性只读视图
     *
     * @return 动态属性不可变 Map
     */
    public Map<String, Object> getAttributes() {
        return Collections.unmodifiableMap(attributes);
    }
}
