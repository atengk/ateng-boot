package io.github.atengk.boot.flyway.security;

import io.github.atengk.boot.flyway.config.FlywayConfigurationSupport;
import io.github.atengk.boot.flyway.enums.FlywayResultCode;
import io.github.atengk.boot.flyway.exception.FlywayException;
import io.github.atengk.boot.flyway.guard.ProductionSafetyGuard;
import io.github.atengk.boot.flyway.model.MigrationTarget;
import io.github.atengk.boot.flyway.properties.AtengFlywayProperties;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.migration.JavaMigration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;
import org.springframework.boot.jdbc.DatabaseDriver;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.io.Closeable;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 隔离式高权限 DDL 瞬态执行器
 * <p>
 * 仅在需要执行 Flyway 操作的瞬间按需解密 {@link AtengFlywayProperties.CredentialProperties}、
 * 构建极小短生命周期瞬态连接池并委托给核心回调执行，随后在 {@code finally} 中确定性彻底关闭连接池，
 * 严禁向 Spring 容器暴露额外 {@link DataSource} Bean 或持有已关闭连接池的 {@link Flyway} Bean。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-17
 */
public class TransientFlywayExecutor {

    private static final Logger log = LoggerFactory.getLogger(TransientFlywayExecutor.class);
    private static final String HIKARI_CLASS = "com.zaxxer.hikari.HikariDataSource";

    private final AtengFlywayProperties atengProperties;
    private final FlywayProperties flywayProperties;
    private final FlywayPasswordDecryptor passwordDecryptor;
    private final ResourceLoader resourceLoader;
    private final List<FlywayConfigurationCustomizer> customizers;
    private final List<JavaMigration> javaMigrations;
    private final List<Callback> callbacks;
    private final ProductionSafetyGuard safetyGuard;
    private final ObjectProvider<DataSource> runtimeDataSourceProvider;

    private final AtomicInteger activeTransientPoolCount = new AtomicInteger(0);
    private final AtomicLong closedTransientPoolCount = new AtomicLong(0);

    /**
     * 构造隔离式瞬态 Flyway 执行器
     *
     * @param atengProperties           ateng-boot-flyway 配置属性
     * @param flywayProperties          Spring Boot 原生 Flyway 配置属性
     * @param passwordDecryptor         密码瞬态解密器
     * @param resourceLoader            资源加载器
     * @param customizers               配置定制器列表
     * @param javaMigrations            Java 迁移脚本列表
     * @param callbacks                 生命周期回调列表
     * @param safetyGuard               生产安全守卫
     * @param runtimeDataSourceProvider 运行期数据源提供者（仅在缺省凭据 URL 时只读探测 URL）
     */
    public TransientFlywayExecutor(AtengFlywayProperties atengProperties,
                                   FlywayProperties flywayProperties,
                                   FlywayPasswordDecryptor passwordDecryptor,
                                   ResourceLoader resourceLoader,
                                   List<FlywayConfigurationCustomizer> customizers,
                                   List<JavaMigration> javaMigrations,
                                   List<Callback> callbacks,
                                   ProductionSafetyGuard safetyGuard,
                                   ObjectProvider<DataSource> runtimeDataSourceProvider) {
        this.atengProperties = Objects.requireNonNull(atengProperties, "atengProperties 不能为空");
        this.flywayProperties = Objects.requireNonNull(flywayProperties, "flywayProperties 不能为空");
        this.passwordDecryptor = Objects.requireNonNull(passwordDecryptor, "passwordDecryptor 不能为空");
        this.resourceLoader = resourceLoader;
        this.customizers = customizers != null ? customizers : List.of();
        this.javaMigrations = javaMigrations != null ? javaMigrations : List.of();
        this.callbacks = callbacks != null ? callbacks : List.of();
        this.safetyGuard = safetyGuard;
        this.runtimeDataSourceProvider = runtimeDataSourceProvider;
    }

    /**
     * 在瞬态隔离连接的生命周期作用域内构建并执行 {@link Flyway} 操作
     *
     * @param targetKey 目标数据源标识
     * @param action    基于 Flyway 实例的业务操作回调
     * @param <T>       返回值类型
     * @return 操作执行结果
     */
    public <T> T execute(String targetKey, Function<Flyway, T> action) {
        return execute(MigrationTarget.builder()
                .targetKey(targetKey)
                .credential(atengProperties.getCredential())
                .build(), action);
    }

    /**
     * 基于完整迁移目标描述符在瞬态隔离连接作用域内执行 {@link Flyway} 操作（支持 L2 独立加密 DDL 凭据）
     *
     * @param target 迁移目标描述符
     * @param action 基于 Flyway 实例的业务操作回调
     * @param <T>    返回值类型
     * @return 操作执行结果
     */
    public <T> T execute(MigrationTarget target, Function<Flyway, T> action) {
        Objects.requireNonNull(target, "target 不能为空");
        Objects.requireNonNull(action, "action 不能为空");
        AtengFlywayProperties.CredentialProperties credential = target.getCredential() != null
                && target.getCredential().isConfigured()
                ? target.getCredential()
                : atengProperties.getCredential();
        if (credential == null || !credential.isConfigured()) {
            throw new FlywayException(FlywayResultCode.CONFIG_INVALID,
                    "目标 [" + target.getTargetKey() + "] 未配置有效的隔离式 DDL 凭据");
        }

        String jdbcUrl = resolveEffectiveJdbcUrl(credential, target.getDataSource());
        String username = credential.getUsername();
        String plainPassword = passwordDecryptor.decrypt(credential.getPassword(), credential);

        DataSource transientDataSource = createTransientDataSource(
                target.getTargetKey(), credential, jdbcUrl, username, plainPassword);
        activeTransientPoolCount.incrementAndGet();
        try {
            Flyway flyway = buildFlyway(target, transientDataSource);
            return action.apply(flyway);
        } finally {
            closeQuietly(transientDataSource, target.getTargetKey());
            activeTransientPoolCount.decrementAndGet();
            closedTransientPoolCount.incrementAndGet();
        }
    }

    /**
     * 基于既有物理数据源构建受管 {@link Flyway} 实例（非隔离凭据模式使用，复用容器物理池不创建额外连接池）
     *
     * @param target 迁移目标描述符
     * @return 受管 Flyway 实例
     */
    public Flyway buildFlyway(MigrationTarget target) {
        Objects.requireNonNull(target, "target 不能为空");
        return buildFlyway(target, target.getDataSource());
    }

    private Flyway buildFlyway(MigrationTarget target, DataSource effectiveDataSource) {
        return FlywayConfigurationSupport.buildFlyway(
                target,
                effectiveDataSource,
                flywayProperties,
                atengProperties,
                resourceLoader,
                customizers,
                javaMigrations,
                callbacks,
                safetyGuard
        );
    }

    /**
     * 获取当前尚未关闭的瞬态连接池计数（用于健康审计与测试零泄漏校验）
     *
     * @return 当前活跃瞬态池数量
     */
    public int getActiveTransientPoolCount() {
        return activeTransientPoolCount.get();
    }

    /**
     * 获取累计已确定性关闭的瞬态连接池数量
     *
     * @return 累计已关闭瞬态池数量
     */
    public long getClosedTransientPoolCount() {
        return closedTransientPoolCount.get();
    }

    private String resolveEffectiveJdbcUrl(AtengFlywayProperties.CredentialProperties credential,
                                           DataSource targetDataSource) {
        if (StringUtils.hasText(credential.getUrl())) {
            return credential.getUrl().trim();
        }
        DataSource candidateDs = targetDataSource != null
                ? targetDataSource
                : (runtimeDataSourceProvider != null ? runtimeDataSourceProvider.getIfAvailable() : null);
        if (candidateDs != null) {
            try {
                String extracted = JdbcUtils.extractDatabaseMetaData(candidateDs, DatabaseMetaData::getURL);
                if (StringUtils.hasText(extracted)) {
                    return extracted;
                }
            } catch (Exception e) {
                throw new FlywayException(
                        FlywayResultCode.CONFIG_INVALID,
                        "无法从运行期数据源提取 JDBC URL 以搭建隔离 DDL 连接: "
                                + FlywayConfigurationSupport.sanitizeSensitiveText(e.getMessage()),
                        e
                );
            }
        }
        if (StringUtils.hasText(flywayProperties.getUrl())) {
            return flywayProperties.getUrl().trim();
        }
        throw new FlywayException(FlywayResultCode.CONFIG_INVALID,
                "隔离 DDL 凭据模式未配置可用 JDBC URL（请设置 ateng.boot.flyway.credential.url 或提供容器 DataSource）");
    }

    private DataSource createTransientDataSource(String targetKey,
                                                 AtengFlywayProperties.CredentialProperties credential,
                                                 String jdbcUrl,
                                                 String username,
                                                 String password) {
        if (ClassUtils.isPresent(HIKARI_CLASS, TransientFlywayExecutor.class.getClassLoader())) {
            String poolSuffix = StringUtils.hasText(targetKey) ? targetKey.trim().replaceAll("[^a-zA-Z0-9._-]", "_") : "default";
            long seq = closedTransientPoolCount.get() + activeTransientPoolCount.get() + 1;
            return HikariTransientDataSourceFactory.create(
                    "ateng-flyway-transient-ddl-" + poolSuffix + "-" + seq,
                    credential, jdbcUrl, username, password);
        }
        return buildFallbackDriverDataSource(credential, jdbcUrl, username, password);
    }

    @SuppressWarnings("unchecked")
    private DataSource buildFallbackDriverDataSource(AtengFlywayProperties.CredentialProperties credential,
                                                     String jdbcUrl,
                                                     String username,
                                                     String password) {
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setUrl(jdbcUrl);
        if (StringUtils.hasText(username)) {
            ds.setUsername(username);
        }
        if (password != null) {
            ds.setPassword(password);
        }
        String driverClassName = StringUtils.hasText(credential.getDriverClassName())
                ? credential.getDriverClassName().trim()
                : DatabaseDriver.fromJdbcUrl(jdbcUrl).getDriverClassName();
        if (StringUtils.hasText(driverClassName)) {
            try {
                Class<?> driverClass = ClassUtils.forName(driverClassName,
                        TransientFlywayExecutor.class.getClassLoader());
                ds.setDriverClass((Class<? extends Driver>) driverClass);
            } catch (ClassNotFoundException e) {
                throw new FlywayException(FlywayResultCode.CONFIG_INVALID,
                        "无法加载隔离 DDL 驱动类: " + driverClassName, e);
            }
        }
        return ds;
    }

    /**
     * 延迟隔离 HikariCP 类引用的静态内部工厂，避免缺少 HikariCP 依赖时类校验阶段触发 {@link NoClassDefFoundError}
     */
    private static final class HikariTransientDataSourceFactory {

        private HikariTransientDataSourceFactory() {
        }

        private static DataSource create(String poolName,
                                         AtengFlywayProperties.CredentialProperties credential,
                                         String jdbcUrl,
                                         String username,
                                         String password) {
            com.zaxxer.hikari.HikariDataSource ds = new com.zaxxer.hikari.HikariDataSource();
            ds.setPoolName(poolName);
            ds.setJdbcUrl(jdbcUrl);
            if (StringUtils.hasText(username)) {
                ds.setUsername(username);
            }
            if (password != null) {
                ds.setPassword(password);
            }
            if (StringUtils.hasText(credential.getDriverClassName())) {
                ds.setDriverClassName(credential.getDriverClassName().trim());
            }
            Duration timeout = credential.getConnectTimeout();
            if (timeout != null && !timeout.isNegative() && !timeout.isZero()) {
                ds.setConnectionTimeout(timeout.toMillis());
            }
            // Flyway 迁移过程会同时持有主连接（元数据/历史表/锁）与执行连接，瞬态池上限至少需大于 1 以免自锁
            ds.setMaximumPoolSize(4);
            ds.setMinimumIdle(0);
            return ds;
        }
    }

    private void closeQuietly(DataSource dataSource, String targetKey) {
        if (dataSource == null) {
            return;
        }
        try {
            if (dataSource instanceof Closeable closeable) {
                closeable.close();
            } else if (dataSource instanceof AutoCloseable autoCloseable) {
                autoCloseable.close();
            }
        } catch (Exception e) {
            log.warn("关闭隔离 DDL 瞬态数据源时出现异常 [target={}]: {}",
                    targetKey, FlywayConfigurationSupport.sanitizeSensitiveText(e.getMessage()));
        }
    }
}
