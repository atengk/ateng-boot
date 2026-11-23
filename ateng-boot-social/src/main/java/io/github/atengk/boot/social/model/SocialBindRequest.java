package io.github.atengk.boot.social.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 社交账号两阶段绑定请求对象
 * <p>
 * 消费阅后即焚防重放票据 {@code bindTicket} 完成本地系统账号与第三方社交身份的关联。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SocialBindRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 两阶段高熵绑定票据
     */
    @NotBlank(message = "两阶段绑定票据 bindTicket 不能为空")
    private String bindTicket;

    /**
     * 本地用户唯一标识
     */
    @NotBlank(message = "本地用户标识 userId 不能为空")
    private String userId;

    /**
     * 客户端设备类型
     */
    private String deviceType;

    /**
     * 业务登录类型/账号标识
     */
    private String loginType;
}
