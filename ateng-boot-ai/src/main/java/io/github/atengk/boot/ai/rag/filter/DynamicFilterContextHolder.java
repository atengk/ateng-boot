package io.github.atengk.boot.ai.rag.filter;

import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import io.github.atengk.boot.core.context.TenantContextHolder;
import org.springframework.util.StringUtils;

/**
 * RAG 知识库检索动态元数据权限过滤上下文持有者
 * <p>
 * 提供基于 {@link ThreadLocal} 的动态过滤条件作用域绑定，并在发起向量检索时自动感知当前租户上下文
 * ({@link TenantContextHolder})，将 {@code tenant_id} 过滤条件与调用方显式指定的动态过滤表达式进行安全下推与复合合并。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public final class DynamicFilterContextHolder {

    private static final ThreadLocal<Filter> DYNAMIC_FILTER_HOLDER = new ThreadLocal<>();

    private DynamicFilterContextHolder() {
    }

    /**
     * 作用域 AutoCloseable 资源句柄
     */
    public interface DynamicFilterScope extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * 在当前线程作用域内绑定动态元数据过滤条件
     *
     * @param filter 动态过滤条件
     * @return 作用域上下文资源 (可在 try-with-resources 块中自动清理还原)
     */
    public static DynamicFilterScope useFilter(Filter filter) {
        Filter previous = DYNAMIC_FILTER_HOLDER.get();
        DYNAMIC_FILTER_HOLDER.set(filter);
        return () -> {
            if (previous != null) {
                DYNAMIC_FILTER_HOLDER.set(previous);
            } else {
                DYNAMIC_FILTER_HOLDER.remove();
            }
        };
    }

    /**
     * 设置当前线程的动态过滤条件
     *
     * @param filter 过滤条件
     */
    public static void setFilter(Filter filter) {
        if (filter != null) {
            DYNAMIC_FILTER_HOLDER.set(filter);
        } else {
            DYNAMIC_FILTER_HOLDER.remove();
        }
    }

    /**
     * 获取当前线程绑定的动态过滤条件
     *
     * @return 当前 Filter 实例，不存在时返回 null
     */
    public static Filter getFilter() {
        return DYNAMIC_FILTER_HOLDER.get();
    }

    /**
     * 清理当前线程的动态过滤条件
     */
    public static void clear() {
        DYNAMIC_FILTER_HOLDER.remove();
    }

    /**
     * 核心过滤下推解析器：将显式传入的过滤条件、线程上下文动态过滤条件与租户上下文隔离条件进行智能复合下推合并
     *
     * @param explicitFilter 调用方显式传入的过滤表达式 (可选)
     * @return 最终复合过滤表达式；若无任何过滤条件则返回 null
     */
    public static Filter resolveFilter(Filter explicitFilter) {
        Filter combined = explicitFilter;

        // 1. 结合当前线程绑定的动态 Filter
        Filter dynamicFilter = DYNAMIC_FILTER_HOLDER.get();
        if (dynamicFilter != null) {
            combined = combined != null ? combined.and(dynamicFilter) : dynamicFilter;
        }

        // 2. 结合 SaaS 租户隔离上下文 (若存在且未忽略)
        String currentTenant = TenantContextHolder.getTenantId();
        boolean ignoreTenant = TenantContextHolder.isIgnoreTenant();
        if (StringUtils.hasText(currentTenant) && !ignoreTenant) {
            Filter tenantFilter = MetadataFilterBuilder.metadataKey("tenant_id").isEqualTo(currentTenant);
            combined = combined != null ? combined.and(tenantFilter) : tenantFilter;
        }

        return combined;
    }
}
