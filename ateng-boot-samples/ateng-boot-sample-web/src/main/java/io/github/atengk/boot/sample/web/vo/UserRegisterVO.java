package io.github.atengk.boot.sample.web.vo;

import io.github.atengk.boot.core.annotation.FieldSensitive;
import io.github.atengk.boot.core.enums.SensitiveType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户注册成功返回视图对象
 * 演示 Long 精度 String 序列化、字段动态脱敏以及显式 Result 封装
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserRegisterVO {

    /**
     * 64 位雪花算法主键 ID（由 Jackson 序列化为 String 防失真）
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
     * 手机号码（出库动态脱敏掩码呈现，如 138****5678）
     */
    @FieldSensitive(SensitiveType.MOBILE_PHONE)
    private String phone;
}
