package io.github.atengk.boot.mybatisplus.util;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.model.SortItem;
import io.github.atengk.boot.mybatisplus.annotation.FieldEncrypt;
import io.github.atengk.boot.mybatisplus.dynamicquery.annotation.DynamicQueryIgnore;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * MyBatis-Plus 分页模型与传输层契约转换工具
 * 封装持久层 IPage 与无侵入标准契约 PageResult、PageQuery 的双向映射，消除 ORM 内部属性向外部泄露
 *
 * @author Ateng
 * @since 2026-10-16
 */
public final class PageUtil {

    /**
     * 默认单页最大允许查询记录数（500 条），防御恶意构造超大 size 引发全表内存加载与 OOM
     */
    public static final long DEFAULT_MAX_LIMIT = 500L;

    /**
     * 启发式敏感排序属性黑名单，禁止通过排序泄露信息
     */
    private static final Set<String> SENSITIVE_PROPERTIES = Set.of(
            "password", "pwd", "secret", "token", "credential"
    );

    private PageUtil() {
    }

    /**
     * 将业务传输层分页入参 PageQuery 转换为 MyBatis-Plus 分页对象 Page（默认开启 count 查询）
     *
     * @param query 分页入参（若为 null 则自动降级为默认第一页每页 10 条）
     * @param <T>   实体泛型
     * @return MyBatis-Plus 分页实例
     */
    public static <T> Page<T> toPage(PageQuery query) {
        return toPage(query, (Class<T>) null, true, DEFAULT_MAX_LIMIT);
    }

    /**
     * 将业务传输层分页入参 PageQuery 转换为 MyBatis-Plus 分页对象 Page，并显式指定是否执行 COUNT 查询
     *
     * @param query       分页入参（若为 null 或非正数则自动降级为默认第 1 页每页 10 条）
     * @param searchCount 是否执行 COUNT 查询
     * @param <T>         实体泛型
     * @return MyBatis-Plus 分页实例
     */
    public static <T> Page<T> toPage(PageQuery query, boolean searchCount) {
        return toPage(query, (Class<T>) null, searchCount, DEFAULT_MAX_LIMIT);
    }

    /**
     * 将业务传输层分页入参 PageQuery 转换为 MyBatis-Plus 分页对象 Page，支持自定义单页最大记录数上限
     *
     * @param query       分页入参（若为 null 或非正数则自动降级为默认第 1 页每页 10 条）
     * @param searchCount 是否执行 COUNT 查询
     * @param maxLimit    单页最大允许记录数上限（若小于等于 0 则自动采用默认 500）
     * @param <T>         实体泛型
     * @return MyBatis-Plus 分页实例
     */
    public static <T> Page<T> toPage(PageQuery query, boolean searchCount, long maxLimit) {
        return toPage(query, (Class<T>) null, searchCount, maxLimit);
    }

    /**
     * 将业务传输层分页入参 PageQuery 转换为 MyBatis-Plus 分页对象 Page，并依托实体类元数据白名单安全转译排序字段
     *
     * @param query       分页入参
     * @param entityClass 目标实体类型（用于校验与映射排序字段白名单，杜绝未授权列探测与注入）
     * @param <T>         实体泛型
     * @return MyBatis-Plus 分页实例
     */
    public static <T> Page<T> toPage(PageQuery query, Class<T> entityClass) {
        return toPage(query, entityClass, true, DEFAULT_MAX_LIMIT);
    }

    /**
     * 将业务传输层分页入参 PageQuery 转换为 MyBatis-Plus 分页对象 Page，支持指定是否统计总数与实体白名单安全转译
     *
     * @param query       分页入参
     * @param entityClass 目标实体类型
     * @param searchCount 是否执行 COUNT 查询
     * @param <T>         实体泛型
     * @return MyBatis-Plus 分页实例
     */
    public static <T> Page<T> toPage(PageQuery query, Class<T> entityClass, boolean searchCount) {
        return toPage(query, entityClass, searchCount, DEFAULT_MAX_LIMIT);
    }

    /**
     * 将业务传输层分页入参 PageQuery 转换为 MyBatis-Plus 分页对象 Page，支持全维度参数定制与实体白名单安全转译
     * 对非法非正整数页码与页容量实施前置防御性修正，限制单页最大条数，并安全转译 sorts 排序规则
     *
     * @param query       分页入参（若为 null 或非正数则自动降级为默认第 1 页每页 10 条）
     * @param entityClass 目标实体类型（若为 null 则退化为基础驼峰下划线转译）
     * @param searchCount 是否执行 COUNT 查询
     * @param maxLimit    单页最大允许记录数上限（若小于等于 0 则自动采用默认 500）
     * @param <T>         实体泛型
     * @return MyBatis-Plus 分页实例
     */
    public static <T> Page<T> toPage(PageQuery query, Class<T> entityClass, boolean searchCount, long maxLimit) {
        long current = (query == null || query.getCurrent() < 1L) ? 1L : query.getCurrent();
        long rawSize = (query == null || query.getSize() < 1L) ? 10L : query.getSize();
        long limit = maxLimit > 0 ? maxLimit : DEFAULT_MAX_LIMIT;
        long size = Math.min(rawSize, limit);
        Page<T> page = new Page<>(current, size, searchCount);

        if (query != null && query.getSorts() != null && !query.getSorts().isEmpty()) {
            TableInfo tableInfo = (entityClass != null) ? TableInfoHelper.getTableInfo(entityClass) : null;
            for (SortItem sort : query.getSorts()) {
                if (sort == null) {
                    continue;
                }
                String property = sort.getProperty();
                if (property == null || property.trim().isEmpty() || !SortItem.isValidProperty(property.trim())) {
                    // 非法字段平滑过滤，防御 SQL 注入与恶意探测
                    continue;
                }
                String trimmedProp = property.trim();
                String column = resolveColumn(trimmedProp, tableInfo);
                if (column != null && !column.isEmpty()) {
                    OrderItem orderItem = sort.isAsc() ? OrderItem.asc(column) : OrderItem.desc(column);
                    page.addOrder(orderItem);
                }
            }
        }
        return page;
    }

    /**
     * 将业务属性名安全解析为底层物理数据库列名
     * 若提供了实体元数据，严格依托实体字段白名单映射（防御未授权列探测与注入）；否则默认采用驼峰转下划线规则
     *
     * @param property  排序属性名
     * @param tableInfo 实体元数据信息
     * @return 映射后的物理列名；若存在实体元数据且属性未在白名单中则返回 null（平滑忽略）
     */
    private static String resolveColumn(String property, TableInfo tableInfo) {
        if (property == null || property.isBlank()) {
            return null;
        }
        String normalized = property.toLowerCase(Locale.ROOT);
        if (SENSITIVE_PROPERTIES.contains(normalized)) {
            return null;
        }
        if (tableInfo != null) {
            // 1. 匹配主键字段
            if (tableInfo.getKeyProperty() != null && tableInfo.getKeyProperty().equalsIgnoreCase(property)) {
                return tableInfo.getKeyColumn();
            }
            if (tableInfo.getKeyColumn() != null && tableInfo.getKeyColumn().equalsIgnoreCase(property)) {
                return tableInfo.getKeyColumn();
            }
            // 2. 匹配普通字段
            if (tableInfo.getFieldList() != null) {
                for (TableFieldInfo fieldInfo : tableInfo.getFieldList()) {
                    if (fieldInfo.getProperty().equalsIgnoreCase(property) || fieldInfo.getColumn().equalsIgnoreCase(property)) {
                        if (fieldInfo.getField() != null) {
                            if (fieldInfo.getField().isAnnotationPresent(DynamicQueryIgnore.class)
                                    || fieldInfo.getField().isAnnotationPresent(FieldEncrypt.class)) {
                                return null;
                            }
                        }
                        return fieldInfo.getColumn();
                    }
                }
            }
            // 实体元数据存在但未匹配到有效映射，说明是非法/未授权属性，返回 null 执行平滑忽略
            return null;
        }
        // 未指定实体元数据时，默认执行驼峰转下划线清洗
        return StringUtils.camelToUnderline(property);
    }

    /**
     * 将 MyBatis-Plus 持久层分页对象 IPage 转换为标准传输层响应 PageResult
     *
     * @param page MyBatis-Plus 分页结果（若为 null 则返回标准空分页）
     * @param <T>  实体泛型
     * @return 标准传输层分页结果
     */
    public static <T> PageResult<T> toPageResult(IPage<T> page) {
        if (page == null) {
            return PageResult.empty();
        }
        return PageResult.of(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    /**
     * 将 MyBatis-Plus 持久层分页对象 IPage 转换为标准传输层响应 PageResult，并流式映射实体至视图对象 (VO)
     *
     * @param page   MyBatis-Plus 分页结果（若为 null 则返回标准空分页）
     * @param mapper 实体至 VO 的映射转换函数
     * @param <T>    源实体泛型
     * @param <R>    目标视图泛型
     * @return 转换后的标准传输层分页结果
     */
    public static <T, R> PageResult<R> toPageResult(IPage<T> page, Function<? super T, ? extends R> mapper) {
        Objects.requireNonNull(mapper, "映射转换函数 mapper 不能为 null");
        return toPageResult(page).map(mapper);
    }
}
