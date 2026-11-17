package io.github.atengk.boot.flyway.routing;

import io.github.atengk.boot.flyway.enums.BatchExecutionPolicy;
import io.github.atengk.boot.flyway.enums.FlywayResultCode;
import io.github.atengk.boot.flyway.enums.MigrationTargetKind;
import io.github.atengk.boot.flyway.exception.FlywayException;
import io.github.atengk.boot.flyway.model.MigrationTarget;
import io.github.atengk.boot.flyway.properties.AtengFlywayProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 标准单数据源迁移目标路由器（无动态多数据源生态时激活）
 * <p>
 * 将容器主数据源解析为 {@link MigrationTargetKind#PRIMARY}，并兼容通过
 * {@code ateng.boot.flyway.datasources.<dsKey>.credential.url} 配置的独立 DDL URL 目标。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-17
 */
public class SingleDataSourceFlywayTargetResolver implements io.github.atengk.boot.flyway.spi.FlywayTargetResolver {

    private final AtengFlywayProperties properties;
    private final FlywayProperties flywayProperties;
    private final ObjectProvider<DataSource> dataSourceProvider;
    private final DataSource directDataSource;

    /**
     * 构造单数据源目标路由器
     *
     * @param properties         ateng-boot-flyway 配置属性
     * @param dataSourceProvider 主数据源提供者
     */
    public SingleDataSourceFlywayTargetResolver(AtengFlywayProperties properties,
                                                ObjectProvider<DataSource> dataSourceProvider) {
        this(properties, null, dataSourceProvider);
    }

    /**
     * 构造支持独立 {@code spring.flyway.url} 兜底的单数据源目标路由器
     *
     * @param properties         ateng-boot-flyway 配置属性
     * @param flywayProperties   Spring Boot 原生 Flyway 属性（可为 null）
     * @param dataSourceProvider 主数据源提供者
     */
    public SingleDataSourceFlywayTargetResolver(AtengFlywayProperties properties,
                                                FlywayProperties flywayProperties,
                                                ObjectProvider<DataSource> dataSourceProvider) {
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.flywayProperties = flywayProperties;
        this.dataSourceProvider = dataSourceProvider;
        this.directDataSource = null;
    }

    /**
     * 基于直接传入的单数据源实例构造目标路由器
     *
     * @param properties       ateng-boot-flyway 配置属性
     * @param directDataSource 单数据源实例（可为 null）
     */
    public SingleDataSourceFlywayTargetResolver(AtengFlywayProperties properties,
                                                DataSource directDataSource) {
        this(properties, null, directDataSource);
    }

    /**
     * 基于直接传入的单数据源实例与原生 Flyway 属性构造目标路由器
     *
     * @param properties       ateng-boot-flyway 配置属性
     * @param flywayProperties Spring Boot 原生 Flyway 属性（可为 null）
     * @param directDataSource 单数据源实例（可为 null）
     */
    public SingleDataSourceFlywayTargetResolver(AtengFlywayProperties properties,
                                                FlywayProperties flywayProperties,
                                                DataSource directDataSource) {
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.flywayProperties = flywayProperties;
        this.dataSourceProvider = null;
        this.directDataSource = directDataSource;
    }

    @Override
    public List<MigrationTarget> resolveTargets() {
        List<MigrationTarget> targets = new ArrayList<>();
        String primaryKey = StringUtils.hasText(properties.getPrimaryTargetKey())
                ? properties.getPrimaryTargetKey().trim()
                : "default";

        DataSource primaryDs = directDataSource != null
                ? directDataSource
                : (dataSourceProvider != null ? dataSourceProvider.getIfAvailable() : null);
        DataSource unwrappedPrimary = primaryDs != null ? TargetRoutingSupport.unwrapBasicProxy(primaryDs) : null;
        AtengFlywayProperties.DataSourceMigrationProperties primaryOverride = properties.getDatasources().get(primaryKey);
        AtengFlywayProperties.CredentialProperties primaryCred = properties.getCredential() != null
                && properties.getCredential().isConfigured()
                ? properties.getCredential()
                : (primaryOverride != null && primaryOverride.getCredential() != null && primaryOverride.getCredential().isConfigured()
                ? primaryOverride.getCredential()
                : null);

        BatchExecutionPolicy staticPolicy = properties.getStaticFailurePolicy() != null
                ? properties.getStaticFailurePolicy()
                : BatchExecutionPolicy.FAIL_FAST;

        boolean primaryEnabled = primaryOverride == null || primaryOverride.isEnabled();
        boolean hasFlywayUrl = flywayProperties != null && StringUtils.hasText(flywayProperties.getUrl());
        if (primaryEnabled && (unwrappedPrimary != null || primaryCred != null || hasFlywayUrl)) {
            targets.add(MigrationTarget.builder()
                    .targetKey(primaryKey)
                    .kind(MigrationTargetKind.PRIMARY)
                    .dataSource(unwrappedPrimary)
                    .credential(primaryCred)
                    .migrationConfig(primaryOverride)
                    .failurePolicy(staticPolicy)
                    .build());
        }

        if (!CollectionUtils.isEmpty(properties.getDatasources())) {
            for (Map.Entry<String, AtengFlywayProperties.DataSourceMigrationProperties> entry : properties.getDatasources().entrySet()) {
                String dsKey = entry.getKey() != null ? entry.getKey().trim() : null;
                AtengFlywayProperties.DataSourceMigrationProperties cfg = entry.getValue();
                if (!StringUtils.hasText(dsKey) || cfg == null || !cfg.isEnabled() || Objects.equals(dsKey, primaryKey)) {
                    continue;
                }
                if (TargetRoutingSupport.isExcludedOrSlave(dsKey, properties)) {
                    continue;
                }
                AtengFlywayProperties.CredentialProperties cred = cfg.getCredential();
                if (cred != null && cred.isConfigured() && StringUtils.hasText(cred.getUrl())) {
                    targets.add(MigrationTarget.builder()
                            .targetKey(dsKey)
                            .kind(MigrationTargetKind.STATIC_DATASOURCE)
                            .credential(cred)
                            .migrationConfig(cfg)
                            .failurePolicy(staticPolicy)
                            .build());
                } else {
                    throw new FlywayException(FlywayResultCode.TARGET_NOT_FOUND,
                            "单数据源模式下静态配置了额外目标 [" + dsKey + "]，但未提供独立 DDL 连接 URL");
                }
            }
        }
        return Collections.unmodifiableList(targets);
    }
}
