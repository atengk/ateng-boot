package io.github.atengk.boot.mybatisplus.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

/**
 * 泛型主键四要素全审计持久层实体抽象基类
 * 在 BaseGenericTimeEntity 基础上实现 Auditable 契约，追加创建人与修改人自动填充
 *
 * @param <ID> 主键类型
 * @author Ateng
 * @since 2026-11-20
 */
@Getter
@Setter
@ToString(callSuper = true)
public abstract class BaseGenericAuditEntity<ID extends Serializable> extends BaseGenericTimeEntity<ID> implements Auditable {

    private static final long serialVersionUID = 1L;

    /**
     * 创建人唯一标识（插入时自动填充）
     */
    @TableField(fill = FieldFill.INSERT)
    private String createBy;

    /**
     * 修改人唯一标识（插入与更新时自动填充）
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updateBy;
}
