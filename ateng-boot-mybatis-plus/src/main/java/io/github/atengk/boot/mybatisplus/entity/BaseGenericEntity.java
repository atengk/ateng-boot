package io.github.atengk.boot.mybatisplus.entity;

import lombok.ToString;

import java.io.Serializable;

/**
 * 泛型主键持久层实体抽象顶层基类
 * 允许业务自定义主键类型（如 String、Long、UUID 等）与主键生成策略
 *
 * @param <ID> 主键类型
 * @author Ateng
 * @since 2026-10-16
 */
@ToString
public abstract class BaseGenericEntity<ID extends Serializable> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 获取主键 ID
     *
     * @return 主键 ID
     */
    public abstract ID getId();

    /**
     * 设置主键 ID
     *
     * @param id 主键 ID
     */
    public abstract void setId(ID id);
}
