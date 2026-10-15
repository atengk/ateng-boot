package io.github.atengk.boot.mybatisplus.util;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;

import java.util.Objects;
import java.util.function.Function;

/**
 * MyBatis-Plus 分页模型与传输层契约转换工具
 * 封装持久层 IPage 与无侵入标准契约 PageResult、PageQuery 的双向映射，消除 ORM 内部属性向外部泄露
 *
 * @author Ateng
 * @since 2026-10-16
 */
public final class PageUtil {

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
        return toPage(query, true);
    }

    /**
     * 将业务传输层分页入参 PageQuery 转换为 MyBatis-Plus 分页对象 Page，并显式指定是否执行 COUNT 查询
     * 对非法非正整数页码与页容量实施前置防御性修正
     *
     * @param query       分页入参（若为 null 或非正数则自动降级为默认第 1 页每页 10 条）
     * @param searchCount 是否执行 COUNT 查询
     * @param <T>         实体泛型
     * @return MyBatis-Plus 分页实例
     */
    public static <T> Page<T> toPage(PageQuery query, boolean searchCount) {
        long current = (query == null || query.getCurrent() < 1L) ? 1L : query.getCurrent();
        long size = (query == null || query.getSize() < 1L) ? 10L : query.getSize();
        return new Page<>(current, size, searchCount);
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
