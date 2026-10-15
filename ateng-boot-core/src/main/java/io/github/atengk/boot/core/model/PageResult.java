package io.github.atengk.boot.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 统一标准分页响应结果实体
 * 传输层与底层 ORM（如 MyBatis-Plus）彻底解耦，仅包含标准 5 要素，杜绝 ORM 内部属性泄露
 *
 * @param <T> 列表项数据类型
 * @author Ateng
 * @since 2026-10-16
 */
@Getter
@Setter
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 当前页数据记录列表
     */
    private List<T> records = Collections.emptyList();

    /**
     * 总记录数
     */
    private long total;

    /**
     * 每页显示条数（默认 10）
     */
    private long size = 10L;

    /**
     * 当前页码（从 1 开始，默认 1）
     */
    private long current = 1L;

    /**
     * 总页数
     */
    private long pages;

    /**
     * 无参构造方法（用于 JSON 反序列化）
     */
    public PageResult() {
    }

    /**
     * 全属性构造方法
     *
     * @param records 数据记录列表
     * @param total   总记录数
     * @param current 当前页码
     * @param size    每页显示条数
     * @param pages   总页数
     */
    public PageResult(List<T> records, long total, long current, long size, long pages) {
        this.records = records != null ? records : Collections.emptyList();
        this.total = total;
        this.current = current;
        this.size = size;
        this.pages = pages;
    }

    /**
     * 基于核心要素自动计算总页数构造方法
     *
     * @param records 数据记录列表
     * @param total   总记录数
     * @param current 当前页码
     * @param size    每页显示条数
     */
    public PageResult(List<T> records, long total, long current, long size) {
        this(records, total, current, size, computePages(total, size));
    }

    /**
     * 静态工厂方法：基于数据与分页要素构建 PageResult
     *
     * @param records 数据记录列表
     * @param total   总记录数
     * @param current 当前页码
     * @param size    每页显示条数
     * @param <T>     数据泛型
     * @return 分页结果实例
     */
    public static <T> PageResult<T> of(List<T> records, long total, long current, long size) {
        return new PageResult<>(records, total, current, size);
    }

    /**
     * 静态工厂方法：基于全要素构建 PageResult
     *
     * @param records 数据记录列表
     * @param total   总记录数
     * @param current 当前页码
     * @param size    每页显示条数
     * @param pages   总页数
     * @param <T>     数据泛型
     * @return 分页结果实例
     */
    public static <T> PageResult<T> of(List<T> records, long total, long current, long size, long pages) {
        return new PageResult<>(records, total, current, size, pages);
    }

    /**
     * 静态工厂方法：构建默认空分页结果
     *
     * @param <T> 数据泛型
     * @return 空分页结果（records 为空集合，total 为 0）
     */
    public static <T> PageResult<T> empty() {
        return new PageResult<>(Collections.emptyList(), 0L, 1L, 10L, 0L);
    }

    /**
     * 静态工厂方法：构建指定页码与容量的空分页结果
     *
     * @param current 当前页码
     * @param size    单页大小
     * @param <T>     数据泛型
     * @return 空分页结果
     */
    public static <T> PageResult<T> empty(long current, long size) {
        return new PageResult<>(Collections.emptyList(), 0L, current, size, 0L);
    }

    /**
     * 流式类型转换映射：将当前分页中的 records 逐项转换为目标类型，保留分页元数据
     *
     * @param mapper 转换函数
     * @param <R>    目标泛型
     * @return 转换后的目标分页实例
     */
    public <R> PageResult<R> map(Function<? super T, ? extends R> mapper) {
        Objects.requireNonNull(mapper, "映射转换函数 mapper 不能为 null");
        List<R> mappedRecords = this.records.stream()
                .map(mapper)
                .collect(Collectors.toList());
        return new PageResult<>(mappedRecords, this.total, this.current, this.size, this.pages);
    }

    /**
     * 防御性设置记录列表，杜绝 null 赋值
     *
     * @param records 数据记录列表
     */
    public void setRecords(List<T> records) {
        this.records = records != null ? records : Collections.emptyList();
    }

    /**
     * 计算总页数
     *
     * @param total 总记录数
     * @param size  每页大小
     * @return 总页数
     */
    private static long computePages(long total, long size) {
        if (total <= 0 || size <= 0) {
            return 0L;
        }
        return (total + size - 1) / size;
    }
}
