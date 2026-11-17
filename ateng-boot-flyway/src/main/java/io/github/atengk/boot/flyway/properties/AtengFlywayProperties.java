package io.github.atengk.boot.flyway.properties;

import io.github.atengk.boot.flyway.enums.FlywayExecutionMode;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Flyway 数据库版本化迁移企业级治理配置属性
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
@ConfigurationProperties(prefix = "ateng.boot.flyway")
public class AtengFlywayProperties {

    /**
     * 是否启用 ateng-boot-flyway 增强底座（关闭时完整退让给 Spring Boot 原生 FlywayAutoConfiguration）
     */
    private boolean enabled = true;

    /**
     * 默认主目标数据源标识名称（用于摘要记录与单库默认目标键）
     */
    private String primaryTargetKey = "default";

    /**
     * 启动迁移执行模式（MIGRATE: 自动迁移; VALIDATE_ONLY: 仅校验不改表; DISABLED: 跳过启动迁移）
     */
    private FlywayExecutionMode mode = FlywayExecutionMode.MIGRATE;

    /**
     * 生产防误操作安全守卫配置
     */
    @NestedConfigurationProperty
    private GuardProperties guard = new GuardProperties();

    /**
     * 隔离式 DDL 迁移凭据配置（高权限瞬态连接，零容器 DataSource/Flyway Bean 污染）
     */
    @NestedConfigurationProperty
    private CredentialProperties credential = new CredentialProperties();

    /**
     * 可观测与健康探针配置
     */
    @NestedConfigurationProperty
    private ObservabilityProperties observability = new ObservabilityProperties();

    /**
     * 生产防误操作硬守卫配置
     */
    @Data
    public static class GuardProperties {

        /**
         * 是否启用生产防误操作安全守卫
         */
        private boolean enabled = true;

        /**
         * 受保护的生产环境 Spring Profile 列表（大小写不敏感匹配）
         */
        private List<String> protectedProfiles = new ArrayList<>(Arrays.asList("prod", "production", "prd"));

        /**
         * 在受保护环境下是否物理禁止任何 clean 操作（无视 spring.flyway.clean-disabled=false）
         */
        private boolean forbidCleanInProtectedProfiles = true;

        /**
         * 是否无条件禁止所有环境执行 clean 操作（包括非生产环境）
         */
        private boolean forbidCleanAlways = false;

        /**
         * 在受保护环境下是否强制拦截 spring.flyway.clean-on-validation-error=true
         */
        private boolean forbidCleanOnValidationError = true;
    }

    /**
     * 隔离式 DDL 高权限迁移凭据配置
     */
    @Data
    public static class CredentialProperties {

        /**
         * 是否允许激活隔离式 DDL 凭据（仅当同时配置了 url/username/password 之一时生效）
         */
        private boolean enabled = true;

        /**
         * 独立 DDL 连接 JDBC URL（未配置时自动复用主库数据源或 spring.flyway.url）
         */
        private String url;

        /**
         * 可选的 JDBC 驱动全限定类名
         */
        private String driverClassName;

        /**
         * 高权限 DDL 迁移账号用户名
         */
        private String username;

        /**
         * 高权限 DDL 迁移账号口令（支持明文以及 enc:sm4: / enc:aes: 密文）
         */
        private String password;

        /**
         * 全局对称解密密钥（用于单段密文 enc:sm4:<cipher> 或 enc:aes:<cipher>，支持 Base64 / Hex / 明文字符）
         */
        private String secretKey;

        /**
         * 全局初始向量 IV（用于单段 CBC 密文，支持 Base64 / Hex / 明文字符）
         */
        private String iv;

        /**
         * 瞬态 DDL 连接超时时间
         */
        private Duration connectTimeout = Duration.ofSeconds(10);

        /**
         * 判断当前配置是否已激活隔离式 DDL 凭据模式
         *
         * @return true 表示已配置有效隔离凭据
         */
        public boolean isConfigured() {
            return enabled && (StringUtils.hasText(url)
                    || StringUtils.hasText(username)
                    || StringUtils.hasText(password));
        }
    }

    /**
     * 可观测性与健康探针配置
     */
    @Data
    public static class ObservabilityProperties {

        /**
         * 是否注册 Actuator FlywayHealthIndicator 健康探针
         */
        private boolean healthEnabled = true;

        /**
         * 健康探针缓存窗口时间（防止高频探查直击数据库；设为 PT0S 时每次实时探查）
         */
        private Duration healthCacheTtl = Duration.ofSeconds(30);

        /**
         * 是否注册 Micrometer 迁移度量指标绑定器
         */
        private boolean metricsEnabled = true;
    }
}
