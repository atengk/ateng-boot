package io.github.atengk.boot.mybatisplus.entity;

import java.io.Serializable;

/**
 * 泛型逻辑删除标记能力契约接口
 * 声明软删除状态标识读写契约，支持与实体主键或占位标识类型对齐
 *
 * @param <T> 逻辑删除标记类型（如 Long、String、Integer）
 * @author Ateng
 * @since 2026-11-20
 */
public interface LogicDeletable<T extends Serializable> {

    /**
     * 获取逻辑删除标记值
     *
     * @return 逻辑删除标记（未删除通常为 0 / 0L / "0"）
     */
    T getDeleted();

    /**
     * 设置逻辑删除标记值
     *
     * @param deleted 逻辑删除标记
     */
    void setDeleted(T deleted);
}
