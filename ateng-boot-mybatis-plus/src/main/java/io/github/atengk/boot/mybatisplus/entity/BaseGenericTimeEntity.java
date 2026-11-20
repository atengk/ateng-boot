package io.github.atengk.boot.mybatisplus.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 泛型主键时间审计持久层实体抽象基类
 * 在 BaseGenericEntity 基础上实现 TimeAuditable 契约，追加创建时间与修改时间自动填充
 *
 * @param <ID> 主键类型
 * @author Ateng
 * @since 2026-11-20
 */
@Getter
@Setter
@ToString(callSuper = true)
public abstract class BaseGenericTimeEntity<ID extends Serializable> extends BaseGenericEntity<ID> implements TimeAuditable {

    private static final long serialVersionUID = 1L;

    /**
     * 创建时间（插入时自动填充）
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间（插入与更新时自动填充）
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
