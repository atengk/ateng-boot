package io.github.atengk.boot.social.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.Instant;

/**
 * 社交账号解绑成功领域事件
 * <p>
 * 当本地用户成功解除与指定第三方平台的绑定关系时发布，
 * 支撑下游系统进行安全审计、风控流水记录及通知提醒。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Getter
public class SocialUnboundEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    /**
     * 本地用户唯一标识
     */
    private final String userId;

    /**
     * 社交应用唯一标识
     */
    private final String appKey;

    /**
     * 解除绑定完成的时间戳
     */
    private final Instant unbindTime;

    /**
     * 构造解绑事件（使用当前即时时间戳）
     *
     * @param source 发布事件的源对象
     * @param userId 本地用户唯一标识
     * @param appKey 社交应用唯一标识
     */
    public SocialUnboundEvent(Object source, String userId, String appKey) {
        this(source, userId, appKey, Instant.now());
    }

    /**
     * 构造解绑事件（指定时间戳）
     *
     * @param source     发布事件的源对象
     * @param userId     本地用户唯一标识
     * @param appKey     社交应用唯一标识
     * @param unbindTime 解除绑定时间戳
     */
    public SocialUnboundEvent(Object source, String userId, String appKey, Instant unbindTime) {
        super(source);
        this.userId = userId;
        this.appKey = appKey;
        this.unbindTime = unbindTime != null ? unbindTime : Instant.now();
    }
}
