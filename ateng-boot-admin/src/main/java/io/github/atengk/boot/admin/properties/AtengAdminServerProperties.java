package io.github.atengk.boot.admin.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Spring Boot Admin Server 企业级监控与管控中心配置属性
 *
 * @author Ateng
 * @since 2026-10-26
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "ateng.boot.admin.server")
public class AtengAdminServerProperties {

    /**
     * 是否启用 Spring Boot Admin Server 企业级自动化装配（默认开启）
     */
    private boolean enabled = true;

    /**
     * 内置控制台默认管理员用户名（默认 admin，支持通过 AdminAuthProvider SPI 扩展接管）
     */
    private String username = "admin";

    /**
     * 内置控制台默认管理员访问密码（默认 admin，生产环境请务必修改或对接认证 SPI）
     */
    private String password = "admin";

    /**
     * 客户端向服务端注册共享密钥（用于 X-Admin-Client-Secret 握手比对，防止非法实例伪造与 SSRF）
     */
    private String clientSecret = "YOUR_CLIENT_SECRET";

    /**
     * 访问客户端 Actuator 敏感端点时的全局兜底 X-Actuator-Token 凭据
     */
    private String defaultActuatorToken = "";

    /**
     * 实例异常状态防抖延时（强类型 Duration，默认 30 秒，避免网络瞬时抖动造成频发误报警）
     */
    private Duration debounceDuration = Duration.ofSeconds(30);

    /**
     * 是否放行 localhost / 127.0.0.1 / ::1 等本地环回地址（本地开发建议 true，生产建议 false）
     */
    private boolean allowLocalInstances = true;

    /**
     * 客户端实例 URL 正则白名单列表，若配置则只有匹配任意一项规则的 URL 方可注册
     */
    private List<String> allowedUrlPatterns = new ArrayList<>();

    /**
     * 是否允许在 Web 控制台执行修改日志级别等高危写操作（默认开启）
     */
    private boolean enableWriteOperations = true;
}
