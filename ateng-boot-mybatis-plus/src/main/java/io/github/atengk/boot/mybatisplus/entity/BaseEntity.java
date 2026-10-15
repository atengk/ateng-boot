package io.github.atengk.boot.mybatisplus.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 标准业务持久层实体抽象基类
 * 在 BaseTimeEntity 基础上追加创建人与修改人审计自动填充，覆盖常规物理删除业务表
 *
 * @author Ateng
 * @since 2026-10-15
 */
@Getter
@Setter
@ToString(callSuper = true)
public abstract class BaseEntity extends BaseTimeEntity {

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
