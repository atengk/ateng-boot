package io.github.atengk.boot.satoken.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Sa-Token 账号被封禁原生 Spring 事件
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Getter
public class SaDisableEvent extends ApplicationEvent {

    private final String loginType;
    private final Object loginId;
    private final String service;
    private final int level;
    private final long disableTime;

    public SaDisableEvent(Object source, String loginType, Object loginId, String service, int level, long disableTime) {
        super(source);
        this.loginType = loginType;
        this.loginId = loginId;
        this.service = service;
        this.level = level;
        this.disableTime = disableTime;
    }
}
