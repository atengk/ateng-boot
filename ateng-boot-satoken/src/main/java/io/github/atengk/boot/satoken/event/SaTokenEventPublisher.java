package io.github.atengk.boot.satoken.event;

import cn.dev33.satoken.listener.SaTokenListener;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Sa-Token 底层生命周期事件与 Spring 原生 ApplicationEvent 事件总线桥接发布器
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Slf4j
@RequiredArgsConstructor
public class SaTokenEventPublisher implements SaTokenListener {

    private final ApplicationEventPublisher eventPublisher;

    @Override
    public void doLogin(String loginType, Object loginId, String tokenValue, SaLoginParameter loginParameter) {
        log.debug("Sa-Token 账号登录事件: loginType={}, loginId={}", loginType, loginId);
        eventPublisher.publishEvent(new SaLoginEvent(this, loginType, loginId, tokenValue, loginParameter));
    }

    @Override
    public void doLogout(String loginType, Object loginId, String tokenValue) {
        log.debug("Sa-Token 账号注销事件: loginType={}, loginId={}", loginType, loginId);
        eventPublisher.publishEvent(new SaLogoutEvent(this, loginType, loginId, tokenValue));
    }

    @Override
    public void doKickout(String loginType, Object loginId, String tokenValue) {
        log.debug("Sa-Token 账号被踢下线事件: loginType={}, loginId={}", loginType, loginId);
        eventPublisher.publishEvent(new SaKickoutEvent(this, loginType, loginId, tokenValue));
    }

    @Override
    public void doReplaced(String loginType, Object loginId, String tokenValue) {
        log.debug("Sa-Token 账号被顶下线事件: loginType={}, loginId={}", loginType, loginId);
        eventPublisher.publishEvent(new SaReplacedEvent(this, loginType, loginId, tokenValue));
    }

    @Override
    public void doDisable(String loginType, Object loginId, String service, int level, long disableTime) {
        log.debug("Sa-Token 账号封禁事件: loginType={}, loginId={}, service={}, level={}, disableTime={}",
                loginType, loginId, service, level, disableTime);
        eventPublisher.publishEvent(new SaDisableEvent(this, loginType, loginId, service, level, disableTime));
    }

    @Override
    public void doUntieDisable(String loginType, Object loginId, String service) {
        log.debug("Sa-Token 账号解封事件: loginType={}, loginId={}, service={}", loginType, loginId, service);
        eventPublisher.publishEvent(new SaUntieDisableEvent(this, loginType, loginId, service));
    }

    @Override
    public void doOpenSafe(String loginType, String tokenValue, String service, long safeTime) {
        log.debug("Sa-Token 开启二级安全认证事件: loginType={}, service={}, safeTime={}", loginType, service, safeTime);
        eventPublisher.publishEvent(new SaOpenSafeEvent(this, loginType, tokenValue, service, safeTime));
    }

    @Override
    public void doCloseSafe(String loginType, String tokenValue, String service) {
        log.debug("Sa-Token 关闭二级安全认证事件: loginType={}, service={}", loginType, service);
        eventPublisher.publishEvent(new SaCloseSafeEvent(this, loginType, tokenValue, service));
    }

    @Override
    public void doCreateSession(String id) {
        log.debug("Sa-Token 创建 Session: id={}", id);
    }

    @Override
    public void doLogoutSession(String id) {
        log.debug("Sa-Token 注销 Session: id={}", id);
    }

    @Override
    public void doRenewTimeout(String tokenValue, Object loginId, String loginType, long timeout) {
        log.debug("Sa-Token 续签 Token: tokenValue={}, loginId={}, timeout={}", tokenValue, loginId, timeout);
    }
}
