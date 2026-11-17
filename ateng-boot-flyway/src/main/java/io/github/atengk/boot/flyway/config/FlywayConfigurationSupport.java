package io.github.atengk.boot.flyway.config;

import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.flyway.guard.ProductionSafetyGuard;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.migration.JavaMigration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;
import org.springframework.boot.jdbc.DatabaseDriver;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.sql.DatabaseMetaData;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flyway 实例配置构建与原生参数映射辅助器
 * <p>
 * 负责将 Spring Boot {@link FlywayProperties}、{@code {vendor}} 方言路径占位符、
 * 容器中注册的 {@link JavaMigration} / {@link Callback} / {@link FlywayConfigurationCustomizer}
 * 与 {@link ProductionSafetyGuard} 安全守卫统合装配为可执行 {@link Flyway} 实例。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-17
 */
public final class FlywayConfigurationSupport {

    private static final Logger log = LoggerFactory.getLogger(FlywayConfigurationSupport.class);
    private static final String VENDOR_PLACEHOLDER = "{vendor}";
    private static final Pattern URI_USER_INFO_PATTERN = Pattern.compile("(://[^:/\\s]+:)([^@/\\s]+)(@)");
    private static final Pattern KEY_VALUE_SECRET_PATTERN = Pattern.compile("(?i)\\b(password|pwd|secret|token|accessKey|secretKey)\\s*=\\s*([^;&\\s,)']+)");

    private FlywayConfigurationSupport() {
    }

    /**
     * 基于 Spring Boot 原生属性与扩展 Bean 构建受管 {@link Flyway} 实例
     *
     * @param targetKey         目标数据源标识
     * @param dataSource        目标数据源
     * @param flywayProperties  Spring Boot 原生 Flyway 配置属性
     * @param resourceLoader    资源加载器
     * @param customizers       配置定制器列表
     * @param javaMigrations    Java 迁移脚本 Bean 列表
     * @param callbacks         Flyway 生命周期回调 Bean 列表
     * @param safetyGuard       生产安全守卫
     * @return 构建完成的 Flyway 实例
     */
    public static Flyway buildFlyway(String targetKey,
                                     DataSource dataSource,
                                     FlywayProperties flywayProperties,
                                     ResourceLoader resourceLoader,
                                     List<FlywayConfigurationCustomizer> customizers,
                                     List<JavaMigration> javaMigrations,
                                     List<Callback> callbacks,
                                     ProductionSafetyGuard safetyGuard) {
        if (safetyGuard != null) {
            safetyGuard.validateConfiguration(targetKey, flywayProperties);
        }

        ClassLoader classLoader = resourceLoader != null
                ? resourceLoader.getClassLoader()
                : Thread.currentThread().getContextClassLoader();
        FluentConfiguration configuration = Flyway.configure(classLoader);

        if (dataSource != null) {
            configuration.dataSource(dataSource);
        } else if (flywayProperties != null && StringUtils.hasText(flywayProperties.getUrl())) {
            configuration.dataSource(
                    flywayProperties.getUrl(),
                    flywayProperties.getUser(),
                    flywayProperties.getPassword()
            );
        }

        applyProperties(configuration, flywayProperties, dataSource, safetyGuard);

        if (!CollectionUtils.isEmpty(javaMigrations)) {
            configuration.javaMigrations(javaMigrations.toArray(new JavaMigration[0]));
        }
        if (!CollectionUtils.isEmpty(callbacks)) {
            configuration.callbacks(callbacks.toArray(new Callback[0]));
        }
        if (!CollectionUtils.isEmpty(customizers)) {
            for (FlywayConfigurationCustomizer customizer : customizers) {
                if (customizer != null) {
                    customizer.customize(configuration);
                }
            }
        }

        // 在定制器执行之后再次强制锁死生产环境守卫，防止外部 customizer 意外开启 clean
        if (safetyGuard != null) {
            boolean effectiveCleanDisabled = safetyGuard.resolveEffectiveCleanDisabled(configuration.isCleanDisabled());
            configuration.cleanDisabled(effectiveCleanDisabled);
            if (safetyGuard.isProtectedEnvironment()) {
                configuration.cleanOnValidationError(false);
            }
        }

        return configuration.load();
    }

    private static void applyProperties(FluentConfiguration configuration,
                                        FlywayProperties properties,
                                        DataSource dataSource,
                                        ProductionSafetyGuard safetyGuard) {
        if (properties == null) {
            return;
        }
        configuration.failOnMissingLocations(properties.isFailOnMissingLocations());
        List<String> resolvedLocations = resolveLocations(properties.getLocations(), dataSource, properties.getUrl());
        if (!CollectionUtils.isEmpty(resolvedLocations)) {
            configuration.locations(resolvedLocations.toArray(new String[0]));
        }
        if (properties.getEncoding() != null) {
            configuration.encoding(properties.getEncoding());
        }
        configuration.connectRetries(properties.getConnectRetries());
        if (properties.getConnectRetriesInterval() != null) {
            configuration.connectRetriesInterval((int) properties.getConnectRetriesInterval().getSeconds());
        }
        configuration.lockRetryCount(properties.getLockRetryCount());
        if (StringUtils.hasText(properties.getDefaultSchema())) {
            configuration.defaultSchema(properties.getDefaultSchema());
        }
        if (!CollectionUtils.isEmpty(properties.getSchemas())) {
            configuration.schemas(properties.getSchemas().toArray(new String[0]));
        }
        configuration.createSchemas(properties.isCreateSchemas());
        if (StringUtils.hasText(properties.getTable())) {
            configuration.table(properties.getTable());
        }
        if (StringUtils.hasText(properties.getTablespace())) {
            configuration.tablespace(properties.getTablespace());
        }
        if (StringUtils.hasText(properties.getBaselineDescription())) {
            configuration.baselineDescription(properties.getBaselineDescription());
        }
        if (StringUtils.hasText(properties.getBaselineVersion())) {
            configuration.baselineVersion(properties.getBaselineVersion());
        }
        if (StringUtils.hasText(properties.getInstalledBy())) {
            configuration.installedBy(properties.getInstalledBy());
        }
        Map<String, String> placeholders = properties.getPlaceholders();
        if (!CollectionUtils.isEmpty(placeholders)) {
            configuration.placeholders(placeholders);
        }
        if (StringUtils.hasText(properties.getPlaceholderPrefix())) {
            configuration.placeholderPrefix(properties.getPlaceholderPrefix());
        }
        if (StringUtils.hasText(properties.getPlaceholderSuffix())) {
            configuration.placeholderSuffix(properties.getPlaceholderSuffix());
        }
        if (StringUtils.hasText(properties.getPlaceholderSeparator())) {
            configuration.placeholderSeparator(properties.getPlaceholderSeparator());
        }
        configuration.placeholderReplacement(properties.isPlaceholderReplacement());
        if (StringUtils.hasText(properties.getSqlMigrationPrefix())) {
            configuration.sqlMigrationPrefix(properties.getSqlMigrationPrefix());
        }
        if (!CollectionUtils.isEmpty(properties.getSqlMigrationSuffixes())) {
            configuration.sqlMigrationSuffixes(properties.getSqlMigrationSuffixes().toArray(new String[0]));
        }
        if (StringUtils.hasText(properties.getSqlMigrationSeparator())) {
            configuration.sqlMigrationSeparator(properties.getSqlMigrationSeparator());
        }
        if (StringUtils.hasText(properties.getRepeatableSqlMigrationPrefix())) {
            configuration.repeatableSqlMigrationPrefix(properties.getRepeatableSqlMigrationPrefix());
        }
        if (StringUtils.hasText(properties.getTarget())) {
            configuration.target(properties.getTarget());
        }
        if (!CollectionUtils.isEmpty(properties.getInitSqls())) {
            configuration.initSql(String.join("\n", properties.getInitSqls()));
        }
        configuration.baselineOnMigrate(properties.isBaselineOnMigrate());
        boolean cleanDisabled = safetyGuard != null
                ? safetyGuard.resolveEffectiveCleanDisabled(properties.isCleanDisabled())
                : properties.isCleanDisabled();
        configuration.cleanDisabled(cleanDisabled);
        configuration.cleanOnValidationError(properties.isCleanOnValidationError());
        configuration.group(properties.isGroup());
        configuration.mixed(properties.isMixed());
        configuration.outOfOrder(properties.isOutOfOrder());
        configuration.skipDefaultCallbacks(properties.isSkipDefaultCallbacks());
        configuration.skipDefaultResolvers(properties.isSkipDefaultResolvers());
        configuration.validateMigrationNaming(properties.isValidateMigrationNaming());
        configuration.validateOnMigrate(properties.isValidateOnMigrate());
        if (StringUtils.hasText(properties.getScriptPlaceholderPrefix())) {
            configuration.scriptPlaceholderPrefix(properties.getScriptPlaceholderPrefix());
        }
        if (StringUtils.hasText(properties.getScriptPlaceholderSuffix())) {
            configuration.scriptPlaceholderSuffix(properties.getScriptPlaceholderSuffix());
        }
        configuration.executeInTransaction(properties.isExecuteInTransaction());
        if (properties.getLoggers() != null && properties.getLoggers().length > 0) {
            configuration.loggers(properties.getLoggers());
        }
        if (properties.getBatch() != null) {
            configuration.batch(properties.getBatch());
        }
        if (properties.getDryRunOutput() != null) {
            configuration.dryRunOutput(properties.getDryRunOutput());
        }
        if (properties.getErrorOverrides() != null && properties.getErrorOverrides().length > 0) {
            configuration.errorOverrides(properties.getErrorOverrides());
        }
        if (properties.getStream() != null) {
            configuration.stream(properties.getStream());
        }
        if (!CollectionUtils.isEmpty(properties.getJdbcProperties())) {
            configuration.jdbcProperties(properties.getJdbcProperties());
        }
        if (properties.getOutputQueryResults() != null) {
            configuration.outputQueryResults(properties.getOutputQueryResults());
        }
        if (properties.getSkipExecutingMigrations() != null) {
            configuration.skipExecutingMigrations(properties.getSkipExecutingMigrations());
        }
        if (!CollectionUtils.isEmpty(properties.getIgnoreMigrationPatterns())) {
            configuration.ignoreMigrationPatterns(properties.getIgnoreMigrationPatterns().toArray(new String[0]));
        }
        if (properties.getDetectEncoding() != null) {
            configuration.detectEncoding(properties.getDetectEncoding());
        }
    }

    /**
     * 解析路径中的 {@code {vendor}} 占位符（与 Spring Boot LocationResolver 保持完全一致）
     *
     * @param locations  原始路径列表
     * @param dataSource 目标数据源
     * @return 解析后的路径列表
     */
    public static List<String> resolveLocations(List<String> locations, DataSource dataSource) {
        return resolveLocations(locations, dataSource, null);
    }

    /**
     * 解析路径中的 {@code {vendor}} 占位符（优先探测 DataSource，其次回退 JDBC URL）
     *
     * @param locations   原始路径列表
     * @param dataSource  目标数据源（可为 null）
     * @param fallbackUrl 备选 JDBC URL（可为 null）
     * @return 解析后的路径列表
     */
    public static List<String> resolveLocations(List<String> locations, DataSource dataSource, String fallbackUrl) {
        if (CollectionUtils.isEmpty(locations)) {
            return locations;
        }
        boolean hasVendor = locations.stream().anyMatch(loc -> loc != null && loc.contains(VENDOR_PLACEHOLDER));
        if (!hasVendor) {
            return locations;
        }
        DatabaseDriver driver = resolveDatabaseDriver(dataSource, fallbackUrl);
        if (driver == DatabaseDriver.UNKNOWN) {
            return locations;
        }
        String vendorId = driver.getId();
        return locations.stream()
                .filter(Objects::nonNull)
                .map(loc -> loc.replace(VENDOR_PLACEHOLDER, vendorId))
                .toList();
    }

    /**
     * 对日志、异常消息与摘要文本中的 JDBC 凭据、密钥参数执行统一脱敏
     *
     * @param message 原始文本
     * @return 脱敏后的安全文本
     */
    public static String sanitizeSensitiveText(String message) {
        if (!StringUtils.hasText(message)) {
            return message;
        }
        String quotedMask = Matcher.quoteReplacement(DesensitizeUtil.password("secret"));
        String sanitized = URI_USER_INFO_PATTERN.matcher(message)
                .replaceAll("$1" + quotedMask + "$3");
        return KEY_VALUE_SECRET_PATTERN.matcher(sanitized)
                .replaceAll("$1=" + quotedMask);
    }

    private static DatabaseDriver resolveDatabaseDriver(DataSource dataSource, String fallbackUrl) {
        if (dataSource != null) {
            try {
                String url = JdbcUtils.extractDatabaseMetaData(dataSource, DatabaseMetaData::getURL);
                DatabaseDriver driver = DatabaseDriver.fromJdbcUrl(url);
                if (driver != DatabaseDriver.UNKNOWN) {
                    return driver;
                }
            } catch (Exception e) {
                log.warn("通过 DataSource 探测数据库方言失败，将尝试回退排查备选 JDBC URL: {}",
                        sanitizeSensitiveText(e.getMessage()));
            }
        }
        if (StringUtils.hasText(fallbackUrl)) {
            return DatabaseDriver.fromJdbcUrl(fallbackUrl);
        }
        return DatabaseDriver.UNKNOWN;
    }
}
