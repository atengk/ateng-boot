package io.github.atengk.boot.liteflow.component;

import org.springframework.core.ResolvableType;

/**
 * 组件泛型上下文类型解析辅助器
 *
 * @author Ateng
 * @since 2026-11-03
 */
final class ComponentContextResolver {

    private ComponentContextResolver() {
    }

    /**
     * 解析组件实现类继承的指定基类首个泛型参数类型
     *
     * @param subclass  实际组件实现类
     * @param baseClass 目标组件基类 Class
     * @param <T>       上下文泛型
     * @return 业务上下文 Class，无法解析时返回 null
     */
    @SuppressWarnings("unchecked")
    static <T> Class<T> resolveGenericContextClass(Class<?> subclass, Class<?> baseClass) {
        if (subclass == null || baseClass == null) {
            return null;
        }
        ResolvableType resolvableType = ResolvableType.forClass(subclass).as(baseClass);
        if (resolvableType.hasGenerics()) {
            Class<?> resolved = resolvableType.getGeneric(0).resolve();
            if (resolved != null && !Object.class.equals(resolved)) {
                return (Class<T>) resolved;
            }
        }
        return null;
    }
}
