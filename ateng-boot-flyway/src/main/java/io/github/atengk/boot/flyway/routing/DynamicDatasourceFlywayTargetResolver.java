package io.github.atengk.boot.flyway.routing;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.ds.GroupDataSource;
import com.baomidou.dynamic.datasource.ds.ItemDataSource;
import io.github.atengk.boot.datasource.routing.AtengDynamicRoutingDataSource;
import io.github.atengk.boot.flyway.enums.BatchExecutionPolicy;
import io.github.atengk.boot.flyway.enums.FlywayResultCode;
import io.github.atengk.boot.flyway.enums.MigrationTargetKind;
import io.github.atengk.boot.flyway.exception.FlywayException;
import io.github.atengk.boot.flyway.model.MigrationTarget;
import io.github.atengk.boot.flyway.properties.AtengFlywayProperties;
import io.github.atengk.boot.flyway.spi.FlywayTargetResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 动态多数据源与主从解包目标路由器（L1 + L2）
 * <p>
 * 与 {@link DynamicRoutingDataSource} / {@code AtengDynamicRoutingDataSource} 协同工作（零源码入侵）：
 * <ul>
 *     <li><b>L1 主从防穿透</b>：通过物理直连解包定位主库 {@code DataSource}，永久剔除 {@code slave}、{@code *_slave} 与只读 {@link GroupDataSource}；</li>
 *     <li><b>L2 静态库隔离纳管</b>：仅纳管在 {@code ateng.boot.flyway.datasources} 中显式声明的业务数据源，未纳管辅助库或未配置租户库保持零入侵。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-11-17
 */
public class DynamicDatasourceFlywayTargetResolver implements FlywayTargetResolver {

    private static final Logger log = LoggerFactory.getLogger(DynamicDatasourceFlywayTargetResolver.class);

    private final AtengFlywayProperties properties;
    private final FlywayProperties flywayProperties;
    private final DataSource containerDataSource;

    /**
     * 构造动态多数据源目标路由器
     *
     * @param properties          ateng-boot-flyway 配置属性
     * @param containerDataSource 容器主数据源 Bean（通常为 AtengDynamicRoutingDataSource）
     */
    public DynamicDatasourceFlywayTargetResolver(AtengFlywayProperties properties,
                                                 DataSource containerDataSource) {
        this(properties, null, containerDataSource);
    }

    /**
     * 构造动态多数据源目标路由器（支持单源退化下识别独立 {@code spring.flyway.url}）
     *
     * @param properties          ateng-boot-flyway 配置属性
     * @param flywayProperties    Spring Boot 原生 Flyway 属性（可为 null）
     * @param containerDataSource 容器主数据源 Bean（可为 null）
     */
    public DynamicDatasourceFlywayTargetResolver(AtengFlywayProperties properties,
                                                 FlywayProperties flywayProperties,
                                                 DataSource containerDataSource) {
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.flywayProperties = flywayProperties;
        this.containerDataSource = containerDataSource;
    }

    @Override
    public List<MigrationTarget> resolveTargets() {
        if (!(containerDataSource instanceof DynamicRoutingDataSource routingDataSource)) {
            return new SingleDataSourceFlywayTargetResolver(properties, flywayProperties, containerDataSource)
                    .resolveTargets();
        }

        Map<String, DataSource> physicalMap = routingDataSource.getDataSources();
        if (physicalMap == null) {
            physicalMap = Collections.emptyMap();
        }
        Map<String, GroupDataSource> groupMap = routingDataSource.getGroupDataSources();
        Set<String> readOnlyGroupMemberKeys = collectReadOnlyGroupMembers(groupMap);

        String primaryKey = resolvePrimaryKey(routingDataSource, physicalMap);
        if (TargetRoutingSupport.isExcludedOrSlave(primaryKey, properties)
                || readOnlyGroupMemberKeys.contains(primaryKey.toLowerCase(Locale.ROOT))) {
            throw new FlywayException(FlywayResultCode.CONFIG_INVALID,
                    "受保护校验失败：动态路由主库标识 [" + primaryKey + "] 被识别为只读从库或排除项，拒绝执行 DDL 迁移");
        }

        List<MigrationTarget> targets = new ArrayList<>();
        BatchExecutionPolicy staticPolicy = resolveStaticPolicy();

        // L1: 解析物理直连主库目标
        AtengFlywayProperties.DataSourceMigrationProperties primaryOverride = properties.getDatasources().get(primaryKey);
        if (primaryOverride == null || primaryOverride.isEnabled()) {
            DataSource primaryPhysical = unwrapDynamicDataSource(physicalMap.get(primaryKey));
            AtengFlywayProperties.CredentialProperties primaryCred = resolveCredential(
                    properties.getCredential(),
                    primaryOverride != null ? primaryOverride.getCredential() : null);
            if (primaryPhysical == null && primaryCred == null) {
                throw new FlywayException(FlywayResultCode.TARGET_NOT_FOUND,
                        "动态路由数据源中未找到物理主库 [" + primaryKey + "]");
            }
            targets.add(MigrationTarget.builder()
                    .targetKey(primaryKey)
                    .kind(MigrationTargetKind.PRIMARY)
                    .dataSource(primaryPhysical)
                    .credential(primaryCred)
                    .migrationConfig(primaryOverride)
                    .failurePolicy(staticPolicy)
                    .build());
        } else {
            log.info("已跳过已显式禁用的主数据源目标 [dsKey={}]", primaryKey);
        }

        // L2: 仅纳管在 ateng.boot.flyway.datasources 中显式声明的非从库静态业务源
        if (!CollectionUtils.isEmpty(properties.getDatasources())) {
            for (Map.Entry<String, AtengFlywayProperties.DataSourceMigrationProperties> entry : properties.getDatasources().entrySet()) {
                String dsKey = entry.getKey() != null ? entry.getKey().trim() : null;
                AtengFlywayProperties.DataSourceMigrationProperties cfg = entry.getValue();
                if (!StringUtils.hasText(dsKey) || cfg == null || !cfg.isEnabled() || Objects.equals(dsKey, primaryKey)) {
                    continue;
                }
                if (TargetRoutingSupport.isExcludedOrSlave(dsKey, properties)
                        || readOnlyGroupMemberKeys.contains(dsKey.toLowerCase(Locale.ROOT))) {
                    log.warn("已跳过静态配置中命中从库/只读分组的数据源目标 [dsKey={}]", dsKey);
                    continue;
                }
                DataSource staticPhysical = unwrapDynamicDataSource(physicalMap.get(dsKey));
                AtengFlywayProperties.CredentialProperties staticCred = cfg.getCredential() != null
                        && cfg.getCredential().isConfigured()
                        ? cfg.getCredential()
                        : null;
                if (staticPhysical == null && (staticCred == null || !StringUtils.hasText(staticCred.getUrl()))) {
                    throw new FlywayException(FlywayResultCode.TARGET_NOT_FOUND,
                            "显式纳管的静态迁移目标 [" + dsKey + "] 在动态数据源中不存在且未配置独立 DDL 连接 URL");
                }
                targets.add(MigrationTarget.builder()
                        .targetKey(dsKey)
                        .kind(MigrationTargetKind.STATIC_DATASOURCE)
                        .dataSource(staticPhysical)
                        .credential(staticCred)
                        .migrationConfig(cfg)
                        .failurePolicy(staticPolicy)
                        .build());
            }
        }
        return Collections.unmodifiableList(targets);
    }

    /**
     * 解包动态路由生态的 {@link ItemDataSource} 及代理层，获取最底层物理 {@link DataSource}
     *
     * @param source 容器数据源实例
     * @return 底层物理直连数据源
     */
    public static DataSource unwrapDynamicDataSource(DataSource source) {
        DataSource current = source;
        for (int depth = 0; depth < 8 && current != null; depth++) {
            if (current instanceof ItemDataSource itemDs) {
                DataSource real = itemDs.getRealDataSource();
                if (real != null && real != current) {
                    current = real;
                    continue;
                }
            }
            DataSource next = TargetRoutingSupport.unwrapBasicProxy(current);
            if (next != null && next != current) {
                current = next;
                continue;
            }
            break;
        }
        return current;
    }

    private String resolvePrimaryKey(DynamicRoutingDataSource routingDataSource, Map<String, DataSource> physicalMap) {
        String configuredKey = properties.getPrimaryTargetKey();
        if (StringUtils.hasText(configuredKey)
                && !"default".equalsIgnoreCase(configuredKey.trim())
                && physicalMap.containsKey(configuredKey.trim())) {
            return configuredKey.trim();
        }
        if (routingDataSource instanceof AtengDynamicRoutingDataSource atengRouting) {
            String routingPrimary = atengRouting.getPrimary();
            if (StringUtils.hasText(routingPrimary)) {
                return routingPrimary.trim();
            }
        }
        if (physicalMap.containsKey("master")) {
            return "master";
        }
        return StringUtils.hasText(configuredKey) ? configuredKey.trim() : "master";
    }

    private Set<String> collectReadOnlyGroupMembers(Map<String, GroupDataSource> groupMap) {
        if (CollectionUtils.isEmpty(groupMap)) {
            return Collections.emptySet();
        }
        boolean skipReadOnlyGroup = properties.getRouting() == null || properties.getRouting().isSkipReadOnlyGroup();
        if (!skipReadOnlyGroup) {
            return Collections.emptySet();
        }
        Set<String> excluded = new LinkedHashSet<>();
        for (Map.Entry<String, GroupDataSource> entry : groupMap.entrySet()) {
            String groupName = entry.getKey();
            GroupDataSource group = entry.getValue();
            if (!StringUtils.hasText(groupName)) {
                continue;
            }
            String normalizedGroup = groupName.trim().toLowerCase(Locale.ROOT);
            boolean readOnlyGroup = "slave".equals(normalizedGroup)
                    || normalizedGroup.endsWith("_slave")
                    || normalizedGroup.startsWith("slave_")
                    || normalizedGroup.contains("readonly")
                    || normalizedGroup.contains("read_only")
                    || normalizedGroup.contains("read-only");
            if (!readOnlyGroup) {
                continue;
            }
            excluded.add(normalizedGroup);
            if (group != null && !CollectionUtils.isEmpty(group.getDataSourceMap())) {
                for (String memberKey : group.getDataSourceMap().keySet()) {
                    if (StringUtils.hasText(memberKey)) {
                        excluded.add(memberKey.trim().toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return excluded;
    }

    private BatchExecutionPolicy resolveStaticPolicy() {
        return properties.getStaticFailurePolicy() != null
                ? properties.getStaticFailurePolicy()
                : BatchExecutionPolicy.FAIL_FAST;
    }

    private AtengFlywayProperties.CredentialProperties resolveCredential(
            AtengFlywayProperties.CredentialProperties globalCred,
            AtengFlywayProperties.CredentialProperties targetCred) {
        if (targetCred != null && targetCred.isConfigured()) {
            return targetCred;
        }
        return globalCred != null && globalCred.isConfigured() ? globalCred : null;
    }
}
