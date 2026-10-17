package io.github.atengk.boot.sample.satoken.listener;

import io.github.atengk.boot.satoken.event.SaKickoutEvent;
import io.github.atengk.boot.satoken.event.SaLoginEvent;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 样例工程安全审计事件监听器
 * 演示通过 Spring 原生 @EventListener 监听 Sa-Token 账号登录与踢下线事件
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Slf4j
@Getter
@Component
public class SampleSecurityEventListener {

    private final List<SaLoginEvent> loginEvents = new CopyOnWriteArrayList<>();
    private final List<SaKickoutEvent> kickoutEvents = new CopyOnWriteArrayList<>();

    @EventListener
    public void onLogin(SaLoginEvent event) {
        log.info("【安全事件监听】收到用户登录事件: userId={}, token={}", event.getLoginId(), event.getTokenValue());
        loginEvents.add(event);
    }

    @EventListener
    public void onKickout(SaKickoutEvent event) {
        log.info("【安全事件监听】收到用户被踢下线事件: userId={}, token={}", event.getLoginId(), event.getTokenValue());
        kickoutEvents.add(event);
    }

    public void clear() {
        loginEvents.clear();
        kickoutEvents.clear();
    }
}
