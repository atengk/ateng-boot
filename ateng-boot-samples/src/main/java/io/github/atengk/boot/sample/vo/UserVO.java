package io.github.atengk.boot.sample.vo;

import io.github.atengk.boot.mybatisplus.annotation.FieldSensitive;
import io.github.atengk.boot.mybatisplus.enums.SensitiveType;
import io.github.atengk.boot.sample.entity.UserEntity;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

/**
 * 用户分页展示视图对象 (VO)
 * 传输层专用对外暴露模型，与持久层 UserEntity 彻底解耦
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Getter
@Setter
@ToString
public class UserVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户唯一标识（雪花 Long）
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
     * 手机号码（出库动态脱敏）
     */
    @FieldSensitive(SensitiveType.MOBILE_PHONE)
    private String phone;

    /**
     * 身份证号码（出库动态脱敏）
     */
    @FieldSensitive(SensitiveType.ID_CARD)
    private String idCard;

    /**
     * 实体对象转换为视图对象的工厂方法
     *
     * @param entity 用户实体
     * @return 转换后的视图对象（若实体为 null 则返回 null）
     */
    public static UserVO from(UserEntity entity) {
        if (entity == null) {
            return null;
        }
        UserVO vo = new UserVO();
        vo.setId(entity.getId());
        vo.setName(entity.getName());
        vo.setAge(entity.getAge());
        vo.setEmail(entity.getEmail());
        vo.setPhone(entity.getPhone());
        vo.setIdCard(entity.getIdCard());
        return vo;
    }
}
