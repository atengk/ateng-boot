package io.github.atengk.boot.satoken.event;

import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Sa-Token 账号登录成功原生 Spring 事件
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Getter
public class SaLoginEvent extends ApplicationEvent {

    private final String loginType;
    private final Object loginId;
    private final String tokenValue;
    private final SaLoginParameter loginParameter;

    public SaLoginEvent(Object source, String loginType, Object loginId, String tokenValue, SaLoginParameter loginParameter) {
        super(source);
        this.loginType = loginType;
        this.loginId = loginId;
        this.tokenValue = tokenValue;
        this.loginParameter = loginParameter;
    }
}
