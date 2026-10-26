package io.github.atengk.boot.admin.autoconfigure;

import de.codecentric.boot.admin.server.config.AdminServerMarkerConfiguration;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Spring Boot Admin Server 企业级自动化装配类
 *
 * @author Ateng
 * @since 2026-10-26
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(AdminServerMarkerConfiguration.Marker.class)
@ConditionalOnBean(AdminServerMarkerConfiguration.Marker.class)
@ConditionalOnProperty(prefix = "ateng.boot.admin.server", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AtengAdminServerProperties.class)
public class AtengAdminServerAutoConfiguration {

}
