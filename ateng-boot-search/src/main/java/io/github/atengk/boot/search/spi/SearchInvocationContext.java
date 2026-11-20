package io.github.atengk.boot.search.spi;

import io.github.atengk.boot.search.enums.SearchEngineType;
import io.github.atengk.boot.search.query.SearchQuery;
import lombok.Builder;
import lombok.Getter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 搜索引擎操作拦截上下文
 * <p>
 * 贯穿 {@link SearchInterceptor} 执行链前、后与异常回调，承载当前操作元数据与拦截器间共享属性。
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Getter
public class SearchInvocationContext {

    /**
     * 操作类型标识（如 index、getById、search、count、deleteByQuery、bulk 等）
     */
    private final String operation;

    /**
     * 目标索引名或索引模式（可能为 null）
     */
    private final String index;

    /**
     * 关联文档实体类类型（可能为 null）
     */
    private final Class<?> entityClass;

    /**
     * 分片路由键（可能为 null）
     */
    private final String routing;

    /**
     * 检索查询规格对象（仅查询或条件清理操作非 null）
     */
    private final SearchQuery query;

    /**
     * 底层搜索引擎类型
     */
    private final SearchEngineType engineType;

    /**
     * 拦截器链路间共享属性字典
     */
    private final Map<String, Object> attributes = new LinkedHashMap<>();

    @Builder
    public SearchInvocationContext(String operation,
                                   String index,
                                   Class<?> entityClass,
                                   String routing,
                                   SearchQuery query,
                                   SearchEngineType engineType) {
        this.operation = operation;
        this.index = index;
        this.entityClass = entityClass;
        this.routing = routing;
        this.query = query;
        this.engineType = engineType;
    }

    /**
     * 写入拦截器共享属性
     *
     * @param key   属性键
     * @param value 属性值
     */
    public void setAttribute(String key, Object value) {
        if (key != null) {
            this.attributes.put(key, value);
        }
    }

    /**
     * 读取拦截器共享属性
     *
     * @param key 属性键
     * @param <T> 期望返回类型
     * @return 属性值，不存在时返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return key != null ? (T) this.attributes.get(key) : null;
    }

    /**
     * 获取当前共享属性的只读视图
     *
     * @return 不可变属性 Map
     */
    public Map<String, Object> getAttributes() {
        return Collections.unmodifiableMap(this.attributes);
    }
}
