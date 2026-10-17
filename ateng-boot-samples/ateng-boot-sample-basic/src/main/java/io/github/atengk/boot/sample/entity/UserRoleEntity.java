package io.github.atengk.boot.sample.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.atengk.boot.mybatisplus.entity.BaseIdEntity;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 用户角色关联轻量映射实体
 * 继承极简主键实体基类 BaseIdEntity，映射无审计字段与无版本/逻辑删除的物理表 sys_user_role
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("sys_user_role")
public class UserRoleEntity extends BaseIdEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 用户 ID
     */
    private Long userId;

    /**
     * 角色 ID
     */
    private Long roleId;
}
