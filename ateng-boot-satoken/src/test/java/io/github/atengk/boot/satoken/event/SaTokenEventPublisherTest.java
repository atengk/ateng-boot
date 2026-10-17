package io.github.atengk.boot.satoken.event;

import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * SaTokenEventPublisher 原生 Spring 事件总线广播桥接单元测试
 *
 * @author Ateng
 * @since 2026-10-17
 */
@DisplayName("SaTokenEventPublisher 原生 Spring 事件广播桥接单元测试")
class SaTokenEventPublisherTest {

    @Test
    @DisplayName("测试登录事件触发并正确发布 SaLoginEvent")
    void testDoLogin() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        SaTokenEventPublisher listener = new SaTokenEventPublisher(publisher);

        SaLoginParameter parameter = new SaLoginParameter().setTimeout(3600);
        listener.doLogin("login", "10001", "token-xyz-123", parameter);

        ArgumentCaptor<ApplicationEvent> captor = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(publisher).publishEvent(captor.capture());

        assertThat(captor.getValue()).isInstanceOf(SaLoginEvent.class);
        SaLoginEvent event = (SaLoginEvent) captor.getValue();
        assertThat(event.getLoginType()).isEqualTo("login");
        assertThat(event.getLoginId()).isEqualTo("10001");
        assertThat(event.getTokenValue()).isEqualTo("token-xyz-123");
        assertThat(event.getLoginParameter()).isSameAs(parameter);
    }

    @Test
    @DisplayName("测试注销事件发布 SaLogoutEvent")
    void testDoLogout() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        SaTokenEventPublisher listener = new SaTokenEventPublisher(publisher);

        listener.doLogout("login", "10002", "token-logout");
        ArgumentCaptor<ApplicationEvent> captor = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(SaLogoutEvent.class);
        SaLogoutEvent logoutEvent = (SaLogoutEvent) captor.getValue();
        assertThat(logoutEvent.getLoginId()).isEqualTo("10002");
        assertThat(logoutEvent.getTokenValue()).isEqualTo("token-logout");
    }

    @Test
    @DisplayName("测试踢人下线事件发布 SaKickoutEvent")
    void testDoKickout() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        SaTokenEventPublisher listener = new SaTokenEventPublisher(publisher);

        listener.doKickout("login", "10003", "token-kickout");
        ArgumentCaptor<ApplicationEvent> captor = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(SaKickoutEvent.class);
        SaKickoutEvent kickoutEvent = (SaKickoutEvent) captor.getValue();
        assertThat(kickoutEvent.getLoginId()).isEqualTo("10003");
        assertThat(kickoutEvent.getTokenValue()).isEqualTo("token-kickout");
    }

    @Test
    @DisplayName("测试顶人下线事件发布 SaReplacedEvent")
    void testDoReplaced() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        SaTokenEventPublisher listener = new SaTokenEventPublisher(publisher);

        listener.doReplaced("login", "10004", "token-replaced");
        ArgumentCaptor<ApplicationEvent> captor = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(SaReplacedEvent.class);
        SaReplacedEvent replacedEvent = (SaReplacedEvent) captor.getValue();
        assertThat(replacedEvent.getLoginId()).isEqualTo("10004");
    }

    @Test
    @DisplayName("测试封禁与解封事件发布 SaDisableEvent 与 SaUntieDisableEvent")
    void testDoDisableAndUntie() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        SaTokenEventPublisher listener = new SaTokenEventPublisher(publisher);

        // 封禁
        listener.doDisable("login", "10005", "order_service", 2, 7200L);
        ArgumentCaptor<ApplicationEvent> captorDisable = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(publisher).publishEvent(captorDisable.capture());
        assertThat(captorDisable.getValue()).isInstanceOf(SaDisableEvent.class);
        SaDisableEvent disableEvent = (SaDisableEvent) captorDisable.getValue();
        assertThat(disableEvent.getService()).isEqualTo("order_service");
        assertThat(disableEvent.getLevel()).isEqualTo(2);
        assertThat(disableEvent.getDisableTime()).isEqualTo(7200L);

        // 解封
        ApplicationEventPublisher untiePublisher = mock(ApplicationEventPublisher.class);
        SaTokenEventPublisher untieListener = new SaTokenEventPublisher(untiePublisher);
        untieListener.doUntieDisable("login", "10005", "order_service");
        ArgumentCaptor<ApplicationEvent> captorUntie = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(untiePublisher).publishEvent(captorUntie.capture());
        assertThat(captorUntie.getValue()).isInstanceOf(SaUntieDisableEvent.class);
        SaUntieDisableEvent untieEvent = (SaUntieDisableEvent) captorUntie.getValue();
        assertThat(untieEvent.getService()).isEqualTo("order_service");
    }

    @Test
    @DisplayName("测试开启与关闭二级认证事件发布")
    void testSafeActions() {
        ApplicationEventPublisher openPublisher = mock(ApplicationEventPublisher.class);
        SaTokenEventPublisher openListener = new SaTokenEventPublisher(openPublisher);

        // 开启二级安全认证
        openListener.doOpenSafe("login", "token-safe-1", "payment", 120L);
        ArgumentCaptor<ApplicationEvent> captorOpen = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(openPublisher).publishEvent(captorOpen.capture());
        assertThat(captorOpen.getValue()).isInstanceOf(SaOpenSafeEvent.class);
        SaOpenSafeEvent openSafeEvent = (SaOpenSafeEvent) captorOpen.getValue();
        assertThat(openSafeEvent.getService()).isEqualTo("payment");
        assertThat(openSafeEvent.getSafeTime()).isEqualTo(120L);

        // 关闭二级安全认证
        ApplicationEventPublisher closePublisher = mock(ApplicationEventPublisher.class);
        SaTokenEventPublisher closeListener = new SaTokenEventPublisher(closePublisher);
        closeListener.doCloseSafe("login", "token-safe-1", "payment");
        ArgumentCaptor<ApplicationEvent> captorClose = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(closePublisher).publishEvent(captorClose.capture());
        assertThat(captorClose.getValue()).isInstanceOf(SaCloseSafeEvent.class);
        SaCloseSafeEvent closeSafeEvent = (SaCloseSafeEvent) captorClose.getValue();
        assertThat(closeSafeEvent.getService()).isEqualTo("payment");
    }
}
