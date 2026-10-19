package io.github.atengk.boot.sample.mybatisplus.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.atengk.boot.mybatisplus.annotation.FieldEncrypt;
import io.github.atengk.boot.mybatisplus.entity.BaseFullEntity;
import io.github.atengk.boot.mybatisplus.handler.EncryptTypeHandler;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 系统用户实体对象
 * 继承全要素受管业务实体基类 BaseFullEntity（覆盖五级阶梯体系最高阶、乐观锁、逻辑删除与字段透明加解密）
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Getter
@Setter
@NoArgsConstructor
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
     * 电子邮箱（数据库透明加密存储）
     */
    @FieldEncrypt
    @TableField(typeHandler = EncryptTypeHandler.class)
    private String email;

    /**
     * 手机号码（数据库透明加密存储）
     */
    @FieldEncrypt
    @TableField(typeHandler = EncryptTypeHandler.class)
    private String phone;

    /**
     * 所属部门 ID（用于细粒度数据权限过滤）
     */
    private Long deptId;

    public UserEntity(String name, Integer age, String email, String phone, Long deptId) {
        this.name = name;
        this.age = age;
        this.email = email;
        this.phone = phone;
        this.deptId = deptId;
    }
}
