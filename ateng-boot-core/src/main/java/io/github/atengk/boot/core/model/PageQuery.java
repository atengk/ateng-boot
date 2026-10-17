package io.github.atengk.boot.core.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

/**
 * 分页查询参数基类
 * 封装分页核心入参要素（当前页码与每页容量），避免各业务模块散落定义
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
}
