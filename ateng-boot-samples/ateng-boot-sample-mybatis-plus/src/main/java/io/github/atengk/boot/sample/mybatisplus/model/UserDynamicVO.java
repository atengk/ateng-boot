package io.github.atengk.boot.sample.mybatisplus.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 动态 SQL 用户查询结果视图对象
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Data
public class UserDynamicVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID
     */
    private Long id;

    /**
     * 用户姓名
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
     * 手机号码
     */
    private String phone;

    /**
     * 所属部门 ID
     */
    private Long deptId;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
