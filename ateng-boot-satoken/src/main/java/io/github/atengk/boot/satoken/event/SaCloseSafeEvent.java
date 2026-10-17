package io.github.atengk.boot.satoken.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Sa-Token 关闭二级安全认证原生 Spring 事件
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Getter
public class SaCloseSafeEvent extends ApplicationEvent {

    private final String loginType;
    private final String tokenValue;
    private final String service;

    public SaCloseSafeEvent(Object source, String loginType, String tokenValue, String service) {
        super(source);
        this.loginType = loginType;
        this.tokenValue = tokenValue;
        this.service = service;
    }
}
