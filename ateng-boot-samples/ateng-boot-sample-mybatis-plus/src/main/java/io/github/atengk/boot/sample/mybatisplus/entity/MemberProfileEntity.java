package io.github.atengk.boot.sample.mybatisplus.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.atengk.boot.mybatisplus.entity.BaseEntity;
import io.github.atengk.boot.mybatisplus.handler.AtengJacksonTypeHandler;
import io.github.atengk.boot.mybatisplus.handler.GenericJsonTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 会员画像与偏好设置实体对象
 * 验证 AtengJacksonTypeHandler 与 GenericJsonTypeHandler 对 List、Set、Map、嵌套 POJO（含 LocalDateTime）
 * 以及深层泛型集合（List&lt;ShippingAddress&gt;）的结构化存储与数据库 NULL 空集合安全保底。
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "member_profile", autoResultMap = true)
public class MemberProfileEntity extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 会员昵称
     */
    private String memberName;

    /**
     * 兴趣标签列表（数据库 NULL 时保底返回不可变空 List）
     */
    @TableField(typeHandler = AtengJacksonTypeHandler.class)
    private List<String> tags;

    /**
     * 会员权益角色集合（数据库 NULL 时保底返回不可变空 Set）
     */
    @TableField(typeHandler = AtengJacksonTypeHandler.class)
    private Set<String> roles;

    /**
     * 扩展偏好字典（数据库 NULL 时保底返回不可变空 Map）
     */
    @TableField(typeHandler = AtengJacksonTypeHandler.class)
    private Map<String, Object> extraInfo;

    /**
     * 嵌套复杂会员等级画像（含 LocalDateTime JSR-310 时间属性，数据库 NULL 时返回 null）
     */
    @TableField(typeHandler = AtengJacksonTypeHandler.class)
    private UserProfile profile;

    /**
     * 会员多收货地址薄（基于 GenericJsonTypeHandler 深层嵌套泛型解析，数据库 NULL 时保底返回空 List）
     */
    @TableField(typeHandler = ShippingAddressListTypeHandler.class)
    private List<ShippingAddress> shippingAddresses;

    public MemberProfileEntity(String memberName) {
        this.memberName = memberName;
    }

    /**
     * 嵌套会员等级画像模型
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserProfile implements Serializable {
        private static final long serialVersionUID = 1L;

        private String bio;
        private Integer level;
        private LocalDateTime registerTime;
    }

    /**
     * 会员收货地址明细模型
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ShippingAddress implements Serializable {
        private static final long serialVersionUID = 1L;

        private String label;
        private String city;
        private String detailAddress;
        private Boolean defaultAddress;
    }

    /**
     * 收货地址列表深层泛型 JSON 类型转换器
     */
    public static class ShippingAddressListTypeHandler extends GenericJsonTypeHandler<List<ShippingAddress>> {

        public ShippingAddressListTypeHandler() {
            super();
        }

        public ShippingAddressListTypeHandler(Class<?> type) {
            super(type);
        }

        public ShippingAddressListTypeHandler(Class<?> type, java.lang.reflect.Field field) {
            super(type, field);
        }
    }
}
