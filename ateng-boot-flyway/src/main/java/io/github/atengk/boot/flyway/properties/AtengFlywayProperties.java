package io.github.atengk.boot.flyway.properties;

import io.github.atengk.boot.flyway.enums.FlywayExecutionMode;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

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
}
