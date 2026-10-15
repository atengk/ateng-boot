package io.github.atengk.boot.sample.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.atengk.boot.mybatisplus.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 示例用户实体对象
 * 继承通用实体基类，映射 H2 数据库 sys_user 用户表
 *
 * @author Ateng
 * @since 2026-10-15
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("sys_user")
public class UserEntity extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 用户姓名
     */
    private String name;

    /**
     * 用户年龄
     */
    private Integer age;

    /**
     * 电子邮箱
     */
    private String email;
}
