package io.github.atengk.boot.sample.mybatisplus.model;

import io.github.atengk.boot.core.annotation.FieldSensitive;
import io.github.atengk.boot.core.enums.SensitiveType;
import io.github.atengk.boot.sample.mybatisplus.entity.UserEntity;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 系统用户安全传输视图对象
 * 严格遵循 ADR-0010 与 ADR-0012，持久层与业务内存中保持 100% 真实明文，
 * 仅在 Controller 响应序列化为 JSON 时通过 @FieldSensitive 执行动态掩码脱敏。
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
public class UserVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键雪花 ID
     */
    private Long id;

    /**
     * 用户姓名
     */
    private String name;

    /**
     * 用户年龄
     */
    private Integer age;

    /**
     * 电子邮箱（JSON 响应输出时自动按邮箱规则动态脱敏）
     */
    @FieldSensitive(SensitiveType.EMAIL)
    private String email;

    /**
     * 手机号码（JSON 响应输出时自动按手机号规则动态脱敏）
     */
    @FieldSensitive(SensitiveType.MOBILE_PHONE)
    private String phone;

    /**
     * 所属部门 ID
     */
    private Long deptId;

    /**
     * 乐观锁版本号
     */
    private Integer version;

    /**
     * 创建人
     */
    private String createBy;

    /**
     * 修改人
     */
    private String updateBy;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 修改时间
     */
    private LocalDateTime updateTime;

    /**
     * 将持久层用户实体转换为安全传输视图对象
     *
     * @param entity 用户持久层实体
     * @return 用户视图对象，入参为 null 时返回 null
     */
    public static UserVO fromEntity(UserEntity entity) {
        if (entity == null) {
            return null;
        }
        UserVO vo = new UserVO();
        vo.setId(entity.getId());
        vo.setName(entity.getName());
        vo.setAge(entity.getAge());
        vo.setEmail(entity.getEmail());
        vo.setPhone(entity.getPhone());
        vo.setDeptId(entity.getDeptId());
        vo.setVersion(entity.getVersion());
        vo.setCreateBy(entity.getCreateBy());
        vo.setUpdateBy(entity.getUpdateBy());
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateTime(entity.getUpdateTime());
        return vo;
    }
}
