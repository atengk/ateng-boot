package io.github.atengk.boot.sample.vo;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

/**
 * 用户角色联表展示视图对象 (VO)
 * 纯 POJO 零注解模型，通过 JSqlParser AST 投影分析自动完成字段与表别名映射
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Getter
@Setter
@ToString
public class UserRoleVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户唯一标识
     */
    private Long id;

    /**
     * 姓名
     */
    private String name;

    /**
     * 年龄
     */
    private Integer age;

    /**
     * 电子邮箱
     */
    private String email;

    /**
     * 关联角色 ID
     */
    private Long roleId;
}
