package io.github.atengk.boot.sample.mybatisplus.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 会员画像创建请求载荷对象（与持久层实体完全解耦）
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
public class MemberProfileCreateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 会员昵称
     */
    @NotBlank(message = "会员昵称不能为空")
    private String memberName;

    /**
     * 兴趣标签列表
     */
    private List<String> tags;

    /**
     * 会员角色集合
     */
    private Set<String> roles;

    /**
     * 扩展偏好字典
     */
    private Map<String, Object> extraInfo;

    /**
     * 个人简介
     */
    private String bio;

    /**
     * 会员等级
     */
    private Integer level;

    /**
     * 会员多收货地址列表（深层嵌套泛型传输项）
     */
    private List<ShippingAddressItem> shippingAddresses;

    /**
     * 收货地址传输载荷项
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ShippingAddressItem implements Serializable {

        private static final long serialVersionUID = 1L;

        private String label;
        private String city;
        private String detailAddress;
        private Boolean defaultAddress;
    }
}
