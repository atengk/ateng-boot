package io.github.atengk.boot.social.autoconfigure;

import io.github.atengk.boot.social.session.SaTokenSocialSessionManager;
import io.github.atengk.boot.social.session.SocialSessionManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sa-Token 会话联动组件条件自动装配
 * <p>
 * 仅当宿主应用类路径中存在 {@code io.github.atengk.boot.satoken.util.LoginHelper} 时激活，
 * 将 {@link SaTokenSocialSessionManager} 注册为默认会话管理器。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "io.github.atengk.boot.satoken.util.LoginHelper")
public class SaTokenSocialSessionConfiguration {

    @Bean
    @ConditionalOnMissingBean(SocialSessionManager.class)
    public SocialSessionManager saTokenSocialSessionManager() {
        return new SaTokenSocialSessionManager();
    }
}
