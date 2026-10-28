package io.github.atengk.boot.core.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 分页查询参数基类
 * 封装分页核心入参要素（当前页码、每页容量与多字段排序），避免各业务模块散落定义
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Getter
@Setter
@ToString
public class PageQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 当前页码（从 1 开始，默认 1）
     */
    @Min(value = 1, message = "当前页码必须大于或等于 1")
    private long current = 1L;

    /**
     * 单页记录条数（默认 10）
     */
    @Min(value = 1, message = "单页条数必须大于或等于 1")
    @Max(value = 1000, message = "单页条数最大不能超过 1000")
    private long size = 10L;

    /**
     * 排序规则列表（支持多属性组合排序）
     */
    @Valid
    private List<SortItem> sorts = new ArrayList<>();

    /**
     * 默认构造方法
     */
    public PageQuery() {
    }

    /**
     * 指定当前页与每页大小的构造方法
     *
     * @param current 当前页码
     * @param size    单页大小
     */
    public PageQuery(long current, long size) {
        this.current = current;
        this.size = size;
    }

    /**
     * 获取排序规则列表（保底返回非空列表）
     *
     * @return 排序项列表
     */
    public List<SortItem> getSorts() {
        if (this.sorts == null) {
            this.sorts = new ArrayList<>();
        }
        return this.sorts;
    }

    /**
     * 设置排序规则列表（执行空安全防御）
     *
     * @param sorts 排序项列表
     */
    public void setSorts(List<SortItem> sorts) {
        this.sorts = (sorts == null) ? new ArrayList<>() : new ArrayList<>(sorts);
    }

    /**
     * 添加排序项
     *
     * @param sortItem 排序项实例
     * @return 当前 PageQuery 实例（支持链式调用）
     */
    public PageQuery addSort(SortItem sortItem) {
        if (sortItem != null) {
            getSorts().add(sortItem);
        }
        return this;
    }

    /**
     * 快捷添加排序项
     *
     * @param property 排序属性名称
     * @param asc      true 为升序，false 为降序
     * @return 当前 PageQuery 实例（支持链式调用）
     */
    public PageQuery addSort(String property, boolean asc) {
        return addSort(SortItem.of(property, asc));
    }

    /**
     * 快捷添加排序项
     *
     * @param property  排序属性名称
     * @param direction 排序方向
     * @return 当前 PageQuery 实例（支持链式调用）
     */
    public PageQuery addSort(String property, SortItem.Direction direction) {
        return addSort(SortItem.of(property, direction));
    }

    /**
     * 快捷添加升序排序项
     *
     * @param property 排序属性名称
     * @return 当前 PageQuery 实例（支持链式调用）
     */
    public PageQuery addAsc(String property) {
        return addSort(SortItem.asc(property));
    }

    /**
     * 快捷添加降序排序项
     *
     * @param property 排序属性名称
     * @return 当前 PageQuery 实例（支持链式调用）
     */
    public PageQuery addDesc(String property) {
        return addSort(SortItem.desc(property));
    }

    /**
     * 清空排序规则列表
     *
     * @return 当前 PageQuery 实例
     */
    public PageQuery clearSorts() {
        if (this.sorts != null) {
            this.sorts.clear();
        }
        return this;
    }
}
