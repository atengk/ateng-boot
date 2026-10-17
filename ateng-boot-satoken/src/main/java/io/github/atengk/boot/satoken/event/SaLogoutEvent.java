package io.github.atengk.boot.satoken.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Sa-Token 账号注销下线原生 Spring 事件
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Getter
public class SaLogoutEvent extends ApplicationEvent {

    private final String loginType;
    private final Object loginId;
    private final String tokenValue;

    public SaLogoutEvent(Object source, String loginType, Object loginId, String tokenValue) {
        super(source);
        this.loginType = loginType;
        this.loginId = loginId;
        this.tokenValue = tokenValue;
    }
}
