package io.github.atengk.boot.flyway.autoconfigure;

import io.github.atengk.boot.flyway.enums.FlywayExecutionMode;
import io.github.atengk.boot.flyway.guard.ProductionSafetyGuard;
import io.github.atengk.boot.flyway.properties.AtengFlywayProperties;
import io.github.atengk.boot.flyway.template.FlywayTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;

/**
 * 启动期迁移调度共享辅助器（统一单库与隔离 DDL 凭据两条启动初始化入口）
 *
 * @author Ateng
 * @since 2026-11-17
 */
final class AtengFlywayStartupSupport {

    private static final Logger log = LoggerFactory.getLogger(AtengFlywayStartupSupport.class);

    private AtengFlywayStartupSupport() {
    }

    static void runStartup(FlywayTemplate flywayTemplate,
                           AtengFlywayProperties atengProperties,
                           FlywayProperties flywayProperties,
                           ProductionSafetyGuard safetyGuard) {
        String primaryKey = atengProperties.getPrimaryTargetKey();
        safetyGuard.validateConfiguration(primaryKey, flywayProperties);

        FlywayExecutionMode mode = atengProperties.getMode() != null
                ? atengProperties.getMode()
                : FlywayExecutionMode.MIGRATE;

        switch (mode) {
            case DISABLED -> {
                log.info("ateng-boot-flyway 启动执行模式为 DISABLED，跳过启动期自动迁移 [target={}]", primaryKey);
                flywayTemplate.recordSkippedStartupSummary();
            }
            case VALIDATE_ONLY -> {
                log.info("ateng-boot-flyway 启动执行模式为 VALIDATE_ONLY，执行只读一致性校验 [target={}]", primaryKey);
                flywayTemplate.validate(primaryKey);
            }
            case MIGRATE -> {
                log.info("ateng-boot-flyway 启动执行模式为 MIGRATE，开始执行数据库版本化迁移 [target={}]", primaryKey);
                flywayTemplate.migrate(primaryKey);
            }
        }
    }
}
