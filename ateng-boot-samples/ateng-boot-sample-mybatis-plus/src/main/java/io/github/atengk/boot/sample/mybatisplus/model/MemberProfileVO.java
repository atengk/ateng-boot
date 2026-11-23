package io.github.atengk.boot.sample.mybatisplus.model;

import io.github.atengk.boot.sample.mybatisplus.entity.MemberProfileEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 会员画像与偏好设置传输视图对象（与持久层实体解耦）
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
public class MemberProfileVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键雪花 ID
     */
    private Long id;

    /**
     * 会员昵称
     */
    private String memberName;

    /**
     * 兴趣标签列表（空值保底返回空 List）
     */
    private List<String> tags;

    /**
     * 会员角色集合（空值保底返回空 Set）
     */
    private Set<String> roles;

    /**
     * 扩展偏好字典（空值保底返回空 Map）
     */
    private Map<String, Object> extraInfo;

    /**
     * 嵌套会员等级画像视图（含 LocalDateTime）
     */
    private UserProfileVO profile;

    /**
     * 会员多收货地址视图列表（GenericJsonTypeHandler 深层泛型，空值保底返回空 List）
     */
    private List<ShippingAddressVO> shippingAddresses;

    /**
     * 数据库底层物理存储的原始收货地址 JSON 文本（用于直观核验序列化落盘格式）
     */
    private String rawAddressesJsonInDb;

    /**
     * 创建人
     */
    private String createBy;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 嵌套会员等级画像视图项
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserProfileVO implements Serializable {

        private static final long serialVersionUID = 1L;

        private String bio;
        private Integer level;
        private LocalDateTime registerTime;
    }

    /**
     * 嵌套收货地址视图项
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ShippingAddressVO implements Serializable {

        private static final long serialVersionUID = 1L;

        private String label;
        private String city;
        private String detailAddress;
        private Boolean defaultAddress;
    }

    /**
     * 从持久层实体转换为传输视图对象
     *
     * @param entity 会员画像实体
     * @return 会员画像视图对象
     */
    public static MemberProfileVO fromEntity(MemberProfileEntity entity) {
        if (entity == null) {
            return null;
        }
        MemberProfileVO vo = new MemberProfileVO();
        vo.setId(entity.getId());
        vo.setMemberName(entity.getMemberName());
        vo.setTags(entity.getTags());
        vo.setRoles(entity.getRoles());
        vo.setExtraInfo(entity.getExtraInfo());
        if (entity.getProfile() != null) {
            MemberProfileEntity.UserProfile p = entity.getProfile();
            vo.setProfile(new UserProfileVO(p.getBio(), p.getLevel(), p.getRegisterTime()));
        }
        if (entity.getShippingAddresses() != null) {
            vo.setShippingAddresses(entity.getShippingAddresses().stream()
                    .map(a -> new ShippingAddressVO(a.getLabel(), a.getCity(), a.getDetailAddress(), a.getDefaultAddress()))
                    .toList());
        } else {
            vo.setShippingAddresses(Collections.emptyList());
        }
        vo.setCreateBy(entity.getCreateBy());
        vo.setCreateTime(entity.getCreateTime());
        return vo;
    }
}
