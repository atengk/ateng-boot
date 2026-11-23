package io.github.atengk.boot.social.event;

import io.github.atengk.boot.social.model.SocialUser;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.Instant;

/**
 * 社交登录两阶段绑定票据签发领域事件
 * <p>
 * 当第三方账号尚未关联本地系统用户且未开启自动开户时发布，携带已脱敏的绑定票据摘要，
 * 彻底防御在审计日志或事件监听器中明文泄露高熵绑定凭据。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Getter
public class SocialBindTicketIssuedEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    /**
     * 脱敏后的绑定票据（保留首尾各 4 位字符，中间打码）
     */
    private final String maskedTicket;

    /**
     * 社交应用唯一标识
     */
    private final String appKey;

    /**
     * 第三方标准化用户画像
     */
    private final SocialUser socialUser;

    /**
     * 票据签发时间戳
     */
    private final Instant issuedTime;

    public SocialBindTicketIssuedEvent(Object source,
                                       String maskedTicket,
                                       String appKey,
                                       SocialUser socialUser) {
        this(source, maskedTicket, appKey, socialUser, Instant.now());
    }

    public SocialBindTicketIssuedEvent(Object source,
                                       String maskedTicket,
                                       String appKey,
                                       SocialUser socialUser,
                                       Instant issuedTime) {
        super(source);
        this.maskedTicket = maskedTicket;
        this.appKey = appKey;
        this.socialUser = socialUser;
        this.issuedTime = issuedTime != null ? issuedTime : Instant.now();
    }
}
