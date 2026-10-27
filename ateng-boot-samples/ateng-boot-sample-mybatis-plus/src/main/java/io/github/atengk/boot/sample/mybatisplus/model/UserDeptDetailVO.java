package io.github.atengk.boot.sample.mybatisplus.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户与部门多表联查视图对象
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Data
public class UserDeptDetailVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户 ID
     */
    private Long userId;

    /**
     * 用户姓名
     */
    private String userName;

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
     * 部门 ID
     */
    private Long deptId;

    /**
     * 部门名称
     */
    private String deptName;

    /**
     * 部门状态
     */
    private String deptStatus;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}
