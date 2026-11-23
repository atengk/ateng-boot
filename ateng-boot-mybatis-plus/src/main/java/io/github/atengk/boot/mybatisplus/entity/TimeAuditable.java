package io.github.atengk.boot.mybatisplus.entity;

import java.time.LocalDateTime;

/**
 * 时间审计能力契约接口
 * 声明创建时间与更新时间读写契约，适用于仅追踪时间戳流转的实体
 *
 * @author Ateng
 * @since 2026-11-20
 */
public interface TimeAuditable {

    /**
     * 获取创建时间
     *
     * @return 创建时间
     */
    LocalDateTime getCreateTime();

    /**
     * 设置创建时间
     *
     * @param createTime 创建时间
     */
    void setCreateTime(LocalDateTime createTime);

    /**
     * 获取更新时间
     *
     * @return 更新时间
     */
    LocalDateTime getUpdateTime();

    /**
     * 设置更新时间
     *
     * @param updateTime 更新时间
     */
    void setUpdateTime(LocalDateTime updateTime);
}
