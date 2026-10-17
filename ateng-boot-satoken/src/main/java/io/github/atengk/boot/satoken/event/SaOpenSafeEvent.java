package io.github.atengk.boot.satoken.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Sa-Token 开启二级安全认证原生 Spring 事件
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Getter
public class SaOpenSafeEvent extends ApplicationEvent {

    private final String loginType;
    private final String tokenValue;
    private final String service;
    private final long safeTime;

    public SaOpenSafeEvent(Object source, String loginType, String tokenValue, String service, long safeTime) {
        super(source);
        this.loginType = loginType;
        this.tokenValue = tokenValue;
        this.service = service;
        this.safeTime = safeTime;
    }
}
