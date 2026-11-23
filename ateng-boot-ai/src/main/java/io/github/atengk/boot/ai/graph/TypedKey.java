package io.github.atengk.boot.ai.graph;

import com.fasterxml.jackson.core.type.TypeReference;

import java.util.Objects;

/**
 * 认知状态图强类型键契约对象
 *
 * @param <T> 目标类型
 * @author Ateng
 * @since 2026-11-03
 */
public final class TypedKey<T> {

    /**
     * 系统保留键：人机协作审批结果数据
     */
    public static final TypedKey<Object> APPROVAL_DATA = TypedKey.of("approvalData", Object.class);

    /**
     * 系统保留键：工作流名称
     */
    public static final TypedKey<String> WORKFLOW_NAME = TypedKey.of("workflowName", String.class);

    /**
     * 系统保留键：执行线程会话标识
     */
    public static final TypedKey<String> THREAD_ID = TypedKey.of("threadId", String.class);

    /**
     * 系统保留键：工作流状态
     */
    public static final TypedKey<String> STATUS = TypedKey.of("status", String.class);

    /**
     * 系统保留键：工作流异常或失败信息
     */
    public static final TypedKey<String> ERROR = TypedKey.of("error", String.class);

    private final String name;
    private final Class<T> targetClass;
    private final TypeReference<T> typeReference;

    private TypedKey(String name, Class<T> targetClass, TypeReference<T> typeReference) {
        this.name = Objects.requireNonNull(name, "TypedKey name must not be null");
        this.targetClass = targetClass;
        this.typeReference = typeReference;
    }

    /**
     * 构建基于 Class 的强类型键
     *
     * @param name 键名称
     * @param targetClass 目标类型 Class
     * @param <T> 类型参数
     * @return 强类型键实例
     */
    public static <T> TypedKey<T> of(String name, Class<T> targetClass) {
        return new TypedKey<>(name, Objects.requireNonNull(targetClass, "targetClass must not be null"), null);
    }

    /**
     * 构建基于泛型 TypeReference 的强类型键
     *
     * @param name 键名称
     * @param typeReference 泛型类型引用
     * @param <T> 类型参数
     * @return 强类型键实例
     */
    public static <T> TypedKey<T> of(String name, TypeReference<T> typeReference) {
        return new TypedKey<>(name, null, Objects.requireNonNull(typeReference, "typeReference must not be null"));
    }

    public String name() {
        return name;
    }

    public Class<T> getTargetClass() {
        return targetClass;
    }

    public TypeReference<T> getTypeReference() {
        return typeReference;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TypedKey<?> typedKey)) {
            return false;
        }
        return Objects.equals(name, typedKey.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public String toString() {
        return "TypedKey{name='" + name + '\'' + '}';
    }
}
