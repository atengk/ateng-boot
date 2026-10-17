package io.github.atengk.boot.satoken.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Sa-Token 账号解封原生 Spring 事件
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Getter
public class SaUntieDisableEvent extends ApplicationEvent {

    private final String loginType;
    private final Object loginId;
    private final String service;

    public SaUntieDisableEvent(Object source, String loginType, Object loginId, String service) {
        super(source);
        this.loginType = loginType;
        this.loginId = loginId;
        this.service = service;
    }
}
