package io.github.atengk.boot.sample.satoken.listener;

import io.github.atengk.boot.sample.satoken.vo.SecurityEventVO;
import io.github.atengk.boot.satoken.event.SaCloseSafeEvent;
import io.github.atengk.boot.satoken.event.SaDisableEvent;
import io.github.atengk.boot.satoken.event.SaKickoutEvent;
import io.github.atengk.boot.satoken.event.SaLoginEvent;
import io.github.atengk.boot.satoken.event.SaLogoutEvent;
import io.github.atengk.boot.satoken.event.SaOpenSafeEvent;
import io.github.atengk.boot.satoken.event.SaReplacedEvent;
import io.github.atengk.boot.satoken.event.SaUntieDisableEvent;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 样例工程全景安全审计事件监听器
 * 演示通过 Spring 原生 @EventListener 监听 Sa-Token 账号登录、注销、踢人、顶替、封禁、解封及二级认证事件
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
    private final List<SecurityEventVO> allEvents = new CopyOnWriteArrayList<>();

    @EventListener
    public void onLogin(SaLoginEvent event) {
        log.info("【安全事件监听】收到用户登录事件: userId={}, token={}", event.getLoginId(), event.getTokenValue());
        loginEvents.add(event);
        allEvents.add(SecurityEventVO.builder()
                .eventType("LOGIN")
                .loginType(event.getLoginType())
                .loginId(String.valueOf(event.getLoginId()))
                .tokenValue(event.getTokenValue())
                .details("用户登录成功")
                .timestamp(LocalDateTime.now())
                .build());
    }

    @EventListener
    public void onLogout(SaLogoutEvent event) {
        log.info("【安全事件监听】收到用户注销事件: userId={}, token={}", event.getLoginId(), event.getTokenValue());
        allEvents.add(SecurityEventVO.builder()
                .eventType("LOGOUT")
                .loginType(event.getLoginType())
                .loginId(String.valueOf(event.getLoginId()))
                .tokenValue(event.getTokenValue())
                .details("用户主动注销")
                .timestamp(LocalDateTime.now())
                .build());
    }

    @EventListener
    public void onKickout(SaKickoutEvent event) {
        log.info("【安全事件监听】收到用户被踢下线事件: userId={}, token={}", event.getLoginId(), event.getTokenValue());
        kickoutEvents.add(event);
        allEvents.add(SecurityEventVO.builder()
                .eventType("KICKOUT")
                .loginType(event.getLoginType())
                .loginId(String.valueOf(event.getLoginId()))
                .tokenValue(event.getTokenValue())
                .details("用户被强制踢下线")
                .timestamp(LocalDateTime.now())
                .build());
    }

    @EventListener
    public void onReplaced(SaReplacedEvent event) {
        log.info("【安全事件监听】收到用户被顶替下线事件: userId={}, token={}", event.getLoginId(), event.getTokenValue());
        allEvents.add(SecurityEventVO.builder()
                .eventType("REPLACED")
                .loginType(event.getLoginType())
                .loginId(String.valueOf(event.getLoginId()))
                .tokenValue(event.getTokenValue())
                .details("用户同端被新登录顶替")
                .timestamp(LocalDateTime.now())
                .build());
    }

    @EventListener
    public void onDisable(SaDisableEvent event) {
        log.info("【安全事件监听】收到账号封禁事件: userId={}, service={}, time={}s",
                event.getLoginId(), event.getService(), event.getDisableTime());
        allEvents.add(SecurityEventVO.builder()
                .eventType("DISABLE")
                .loginType(event.getLoginType())
                .loginId(String.valueOf(event.getLoginId()))
                .details("账号被封禁: service=" + event.getService() + ", duration=" + event.getDisableTime() + "s")
                .timestamp(LocalDateTime.now())
                .build());
    }

    @EventListener
    public void onUntieDisable(SaUntieDisableEvent event) {
        log.info("【安全事件监听】收到账号解封事件: userId={}, service={}", event.getLoginId(), event.getService());
        allEvents.add(SecurityEventVO.builder()
                .eventType("UNTIE_DISABLE")
                .loginType(event.getLoginType())
                .loginId(String.valueOf(event.getLoginId()))
                .details("账号解除封禁: service=" + event.getService())
                .timestamp(LocalDateTime.now())
                .build());
    }

    @EventListener
    public void onOpenSafe(SaOpenSafeEvent event) {
        log.info("【安全事件监听】收到开启二级安全认证事件: service={}, time={}s", event.getService(), event.getSafeTime());
        allEvents.add(SecurityEventVO.builder()
                .eventType("OPEN_SAFE")
                .loginType(event.getLoginType())
                .details("开启二级安全认证: service=" + event.getService() + ", time=" + event.getSafeTime() + "s")
                .timestamp(LocalDateTime.now())
                .build());
    }

    @EventListener
    public void onCloseSafe(SaCloseSafeEvent event) {
        log.info("【安全事件监听】收到关闭二级安全认证事件: service={}", event.getService());
        allEvents.add(SecurityEventVO.builder()
                .eventType("CLOSE_SAFE")
                .loginType(event.getLoginType())
                .details("关闭二级安全认证: service=" + event.getService())
                .timestamp(LocalDateTime.now())
                .build());
    }

    public List<SecurityEventVO> getAllEventsList() {
        return Collections.unmodifiableList(allEvents);
    }

    public void clear() {
        loginEvents.clear();
        kickoutEvents.clear();
        allEvents.clear();
    }
}
