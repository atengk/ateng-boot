package io.github.atengk.boot.sample.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.atengk.boot.mybatisplus.annotation.FieldEncrypt;
import io.github.atengk.boot.mybatisplus.annotation.FieldSensitive;
import io.github.atengk.boot.mybatisplus.entity.BaseFullEntity;
import io.github.atengk.boot.mybatisplus.enums.SensitiveType;
import io.github.atengk.boot.mybatisplus.handler.EncryptTypeHandler;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 示例用户实体对象
 * 继承全要素受管业务实体基类，映射 H2 数据库 sys_user 用户表
 *
 * @author Ateng
 * @since 2026-10-15
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName(value = "sys_user", autoResultMap = true)
public class UserEntity extends BaseFullEntity {

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

    /**
     * 手机号码（数据库透明加密存储，视图出库动态脱敏）
     */
    @FieldEncrypt
    @TableField(typeHandler = EncryptTypeHandler.class)
    @FieldSensitive(SensitiveType.MOBILE_PHONE)
    private String phone;

    /**
     * 身份证号码（数据库透明加密存储，视图出库动态脱敏）
     */
    @FieldEncrypt
    @TableField(typeHandler = EncryptTypeHandler.class)
    @FieldSensitive(SensitiveType.ID_CARD)
    private String idCard;
}
