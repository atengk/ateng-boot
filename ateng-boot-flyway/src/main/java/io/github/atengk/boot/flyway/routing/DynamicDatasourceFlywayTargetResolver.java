package io.github.atengk.boot.flyway.routing;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.ds.GroupDataSource;
import com.baomidou.dynamic.datasource.ds.ItemDataSource;
import io.github.atengk.boot.datasource.properties.DatasourceProperties;
import io.github.atengk.boot.datasource.routing.AtengDynamicRoutingDataSource;
import io.github.atengk.boot.datasource.tenant.TenantDataSourceProvider;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 动态多数据源、主从解包与 SaaS 物理租户目标路由器（L1 + L2 + L3）
 * <p>
 * 与 {@link DynamicRoutingDataSource} / {@code AtengDynamicRoutingDataSource} 协同工作（零源码入侵）：
 * <ul>
 *     <li><b>L1 主从防穿透</b>：通过物理直连解包定位主库 {@code DataSource}，永久剔除 {@code slave}、{@code *_slave} 与只读 {@link GroupDataSource}；</li>
 *     <li><b>L2 静态库隔离纳管</b>：仅纳管在 {@code ateng.boot.flyway.datasources} 中显式声明的业务数据源；</li>
 *     <li><b>L3 SaaS 物理租户路由</b>：开启租户迁移后自动归并租户前缀（兼容 {@code tenant_&lt;id&gt;_master}）与 {@link TenantDataSourceProvider} 映射的租户物理库并物理隔离目录。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-11-17
 */
public class DynamicDatasourceFlywayTargetResolver implements FlywayTargetResolver {

    private static final Logger log = LoggerFactory.getLogger(DynamicDatasourceFlywayTargetResolver.class);
    private static final String DEFAULT_TENANT_PREFIX = "tenant_";
    private static final String MASTER_SUFFIX = "_master";

    private final AtengFlywayProperties properties;
    private final FlywayProperties flywayProperties;
    private final DataSource containerDataSource;
    private final DatasourceProperties datasourceProperties;
    private final TenantDataSourceProvider tenantDataSourceProvider;

    /**
     * 构造动态多数据源目标路由器
     *
     * @param properties          ateng-boot-flyway 配置属性
     * @param containerDataSource 容器主数据源 Bean（通常为 AtengDynamicRoutingDataSource）
     */
    public DynamicDatasourceFlywayTargetResolver(AtengFlywayProperties properties,
                                                 DataSource containerDataSource) {
        this(properties, null, containerDataSource, null, null);
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
        this(properties, flywayProperties, containerDataSource, null, null);
    }

    /**
     * 构造支持 L3 SaaS 物理多租户映射解析的完整动态目标路由器
     *
     * @param properties               ateng-boot-flyway 配置属性
     * @param flywayProperties         Spring Boot 原生 Flyway 属性（可为 null）
     * @param containerDataSource      容器主数据源 Bean（可为 null）
     * @param datasourceProperties     ateng-boot-datasource 配置属性（可为 null）
     * @param tenantDataSourceProvider 租户数据源映射解析器（可为 null）
     */
    public DynamicDatasourceFlywayTargetResolver(AtengFlywayProperties properties,
                                                 FlywayProperties flywayProperties,
                                                 DataSource containerDataSource,
                                                 DatasourceProperties datasourceProperties,
                                                 TenantDataSourceProvider tenantDataSourceProvider) {
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.flywayProperties = flywayProperties;
        this.containerDataSource = containerDataSource;
        this.datasourceProperties = datasourceProperties;
        this.tenantDataSourceProvider = tenantDataSourceProvider;
    }

    @Override
    public List<MigrationTarget> resolveTargets() {
        if (!(containerDataSource instanceof DynamicRoutingDataSource routingDataSource)) {
            return new SingleDataSourceFlywayTargetResolver(properties, flywayProperties, containerDataSource)
                    .resolveTargets();
        }

        Map<String, DataSource> physicalMap = safePhysicalMap(routingDataSource);
        Set<String> readOnlyGroupMemberKeys = collectReadOnlyGroupMembers(routingDataSource.getGroupDataSources());

        String primaryKey = resolvePrimaryKey(routingDataSource, physicalMap);
        if (TargetRoutingSupport.isExcludedOrSlave(primaryKey, properties)
                || readOnlyGroupMemberKeys.contains(primaryKey.toLowerCase(Locale.ROOT))) {
            throw new FlywayException(FlywayResultCode.CONFIG_INVALID,
                    "受保护校验失败：动态路由主库标识 [" + primaryKey + "] 被识别为只读从库或排除项，拒绝执行 DDL 迁移");
        }

        List<MigrationTarget> targets = new ArrayList<>();
        BatchExecutionPolicy staticPolicy = resolveStaticPolicy();

        // L1: 解析物理直连主库目标
        addPrimaryTarget(targets, physicalMap, primaryKey, staticPolicy);

        // L3 租户映射预解析（统一归并 TenantDataSourceProvider 与租户前缀，避免 L2/L3 重复执行）
        Map<String, String> tenantDsToId = isTenantMigrationEnabled()
                ? collectTenantCandidates(physicalMap, readOnlyGroupMemberKeys, primaryKey)
                : Collections.emptyMap();

        // L2: 仅纳管在 ateng.boot.flyway.datasources 中显式声明的非从库、非租户静态业务源
        addStaticDatasourceTargets(targets, physicalMap, readOnlyGroupMemberKeys, primaryKey, staticPolicy, tenantDsToId);

        // L3: 纳管全部物理租户库
        addTenantTargets(targets, physicalMap, tenantDsToId);

        return Collections.unmodifiableList(targets);
    }

    @Override
    public boolean isDynamicMountManaged(String dsKey) {
        if (!StringUtils.hasText(dsKey)) {
            return false;
        }
        String normalizedKey = dsKey.trim();
        if (TargetRoutingSupport.isExcludedOrSlave(normalizedKey, properties)) {
            return false;
        }
        if (containerDataSource instanceof DynamicRoutingDataSource routingDataSource) {
            Set<String> readOnlyMembers = collectReadOnlyGroupMembers(routingDataSource.getGroupDataSources());
            if (readOnlyMembers.contains(normalizedKey.toLowerCase(Locale.ROOT))) {
                return false;
            }
            Map<String, DataSource> physicalMap = safePhysicalMap(routingDataSource);
            String primaryKey = resolvePrimaryKey(routingDataSource, physicalMap);
            if (Objects.equals(normalizedKey, primaryKey)) {
                return false;
            }
        }
        AtengFlywayProperties.DataSourceMigrationProperties explicitCfg = properties.getDatasources().get(normalizedKey);
        if (explicitCfg != null) {
            return explicitCfg.isEnabled();
        }
        if (!isTenantMigrationEnabled()) {
            return false;
        }
        Map<String, DataSource> physicalMap = containerDataSource instanceof DynamicRoutingDataSource routingDataSource
                ? safePhysicalMap(routingDataSource)
                : Collections.emptyMap();
        return resolveTenantId(normalizedKey, physicalMap) != null;
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

    private void addPrimaryTarget(List<MigrationTarget> targets,
                                  Map<String, DataSource> physicalMap,
                                  String primaryKey,
                                  BatchExecutionPolicy staticPolicy) {
        AtengFlywayProperties.DataSourceMigrationProperties primaryOverride = properties.getDatasources().get(primaryKey);
        if (primaryOverride != null && !primaryOverride.isEnabled()) {
            log.info("已跳过已显式禁用的主数据源目标 [dsKey={}]", primaryKey);
            return;
        }
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
    }

    private void addStaticDatasourceTargets(List<MigrationTarget> targets,
                                            Map<String, DataSource> physicalMap,
                                            Set<String> readOnlyGroupMemberKeys,
                                            String primaryKey,
                                            BatchExecutionPolicy staticPolicy,
                                            Map<String, String> tenantDsToId) {
        if (CollectionUtils.isEmpty(properties.getDatasources())) {
            return;
        }
        for (Map.Entry<String, AtengFlywayProperties.DataSourceMigrationProperties> entry : properties.getDatasources().entrySet()) {
            String dsKey = entry.getKey() != null ? entry.getKey().trim() : null;
            AtengFlywayProperties.DataSourceMigrationProperties cfg = entry.getValue();
            if (!StringUtils.hasText(dsKey) || cfg == null || !cfg.isEnabled()
                    || Objects.equals(dsKey, primaryKey) || tenantDsToId.containsKey(dsKey)) {
                continue;
            }
            // 预配置的动态租户覆盖项在尚未挂载时保持延后解析，避免误判为缺失静态业务库
            if (isTenantMigrationEnabled() && resolveTenantId(dsKey, physicalMap) != null) {
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

    private void addTenantTargets(List<MigrationTarget> targets,
                                  Map<String, DataSource> physicalMap,
                                  Map<String, String> tenantDsToId) {
        if (tenantDsToId.isEmpty()) {
            return;
        }
        AtengFlywayProperties.TenantMigrationProperties tenantProps = properties.getTenant();
        BatchExecutionPolicy tenantPolicy = tenantProps.getFailurePolicy() != null
                ? tenantProps.getFailurePolicy()
                : BatchExecutionPolicy.FAIL_SAFE;

        for (Map.Entry<String, String> entry : tenantDsToId.entrySet()) {
            String dsKey = entry.getKey();
            String tenantId = entry.getValue();
            AtengFlywayProperties.DataSourceMigrationProperties perTargetOverride = properties.getDatasources().get(dsKey);
            if (perTargetOverride != null && !perTargetOverride.isEnabled()) {
                log.info("已跳过显式禁用的租户数据源目标 [dsKey={}, tenantId={}]", dsKey, tenantId);
                continue;
            }
            DataSource tenantPhysical = unwrapDynamicDataSource(physicalMap.get(dsKey));
            AtengFlywayProperties.CredentialProperties tenantCred = resolveCredential(
                    tenantProps.getCredential(),
                    perTargetOverride != null ? perTargetOverride.getCredential() : null);
            if (tenantPhysical == null && (tenantCred == null || !StringUtils.hasText(tenantCred.getUrl()))) {
                continue;
            }
            targets.add(MigrationTarget.builder()
                    .targetKey(dsKey)
                    .kind(MigrationTargetKind.TENANT)
                    .tenantId(tenantId)
                    .dataSource(tenantPhysical)
                    .credential(tenantCred)
                    .migrationConfig(mergeMigrationConfig(tenantProps, perTargetOverride))
                    .failurePolicy(tenantPolicy)
                    .build());
        }
    }

    private Map<String, String> collectTenantCandidates(Map<String, DataSource> physicalMap,
                                                        Set<String> readOnlyGroupMemberKeys,
                                                        String primaryKey) {
        Map<String, String> dsKeyToTenantId = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : resolveProviderMappings(physicalMap).entrySet()) {
            String dsKey = entry.getKey();
            if (isEligibleTenantKey(dsKey, primaryKey, readOnlyGroupMemberKeys)) {
                dsKeyToTenantId.putIfAbsent(dsKey, entry.getValue());
            }
        }
        String prefix = resolveEffectiveTenantPrefix();
        if (StringUtils.hasText(prefix)) {
            for (String rawKey : physicalMap.keySet()) {
                String dsKey = rawKey != null ? rawKey.trim() : null;
                if (isEligibleTenantKey(dsKey, primaryKey, readOnlyGroupMemberKeys)
                        && !dsKeyToTenantId.containsKey(dsKey)) {
                    String tenantId = extractTenantIdByPrefix(dsKey, prefix);
                    if (StringUtils.hasText(tenantId)) {
                        dsKeyToTenantId.put(dsKey, tenantId);
                    }
                }
            }
        }
        return dsKeyToTenantId;
    }

    private Map<String, String> resolveProviderMappings(Map<String, DataSource> physicalMap) {
        if (tenantDataSourceProvider == null) {
            return Collections.emptyMap();
        }
        Map<String, String> spiMap = tenantDataSourceProvider.getTenantDataSourceMap();
        if (CollectionUtils.isEmpty(spiMap)) {
            return Collections.emptyMap();
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : spiMap.entrySet()) {
            String tenantId = entry.getKey() != null ? entry.getKey().trim() : null;
            String mappedKey = entry.getValue() != null ? entry.getValue().trim() : null;
            if (!StringUtils.hasText(tenantId)) {
                continue;
            }
            String masterKey = tenantDataSourceProvider.resolveMasterDataSourceKey(tenantId);
            String effectiveKey = (StringUtils.hasText(masterKey) && physicalMap.containsKey(masterKey.trim()))
                    ? masterKey.trim()
                    : mappedKey;
            if (StringUtils.hasText(effectiveKey)) {
                resolved.putIfAbsent(effectiveKey, tenantId);
            }
        }
        return resolved;
    }

    private String resolveTenantId(String dsKey, Map<String, DataSource> physicalMap) {
        String fromProvider = resolveProviderMappings(physicalMap).get(dsKey);
        if (StringUtils.hasText(fromProvider)) {
            return fromProvider;
        }
        return extractTenantIdByPrefix(dsKey, resolveEffectiveTenantPrefix());
    }

    private String extractTenantIdByPrefix(String dsKey, String prefix) {
        if (!StringUtils.hasText(prefix) || !StringUtils.hasText(dsKey)
                || !dsKey.startsWith(prefix) || dsKey.length() <= prefix.length()) {
            return null;
        }
        String rawSuffix = dsKey.substring(prefix.length()).trim();
        if (!StringUtils.hasText(rawSuffix)) {
            return null;
        }
        if (rawSuffix.endsWith(MASTER_SUFFIX) && rawSuffix.length() > MASTER_SUFFIX.length()) {
            return rawSuffix.substring(0, rawSuffix.length() - MASTER_SUFFIX.length());
        }
        return rawSuffix;
    }

    private boolean isEligibleTenantKey(String dsKey, String primaryKey, Set<String> readOnlyGroupMemberKeys) {
        if (!StringUtils.hasText(dsKey) || Objects.equals(dsKey, primaryKey)) {
            return false;
        }
        return !TargetRoutingSupport.isExcludedOrSlave(dsKey, properties)
                && !readOnlyGroupMemberKeys.contains(dsKey.toLowerCase(Locale.ROOT));
    }

    private boolean isTenantMigrationEnabled() {
        return properties.getTenant() != null && properties.getTenant().isEnabled();
    }

    private String resolveEffectiveTenantPrefix() {
        if (properties.getTenant() != null && StringUtils.hasText(properties.getTenant().getDataSourcePrefix())) {
            return properties.getTenant().getDataSourcePrefix().trim();
        }
        if (datasourceProperties != null && datasourceProperties.getMultiTenancy() != null
                && StringUtils.hasText(datasourceProperties.getMultiTenancy().getDataSourcePrefix())) {
            return datasourceProperties.getMultiTenancy().getDataSourcePrefix().trim();
        }
        return DEFAULT_TENANT_PREFIX;
    }

    private AtengFlywayProperties.DataSourceMigrationProperties mergeMigrationConfig(
            AtengFlywayProperties.DataSourceMigrationProperties base,
            AtengFlywayProperties.DataSourceMigrationProperties override) {
        AtengFlywayProperties.DataSourceMigrationProperties merged = new AtengFlywayProperties.DataSourceMigrationProperties();
        Optional.ofNullable(override != null && !CollectionUtils.isEmpty(override.getLocations())
                ? override.getLocations() : base.getLocations())
                .filter(list -> !list.isEmpty())
                .ifPresent(list -> merged.setLocations(new ArrayList<>(list)));
        merged.setTable(firstNonBlank(override != null ? override.getTable() : null, base.getTable()));
        merged.setSchemas(override != null && !CollectionUtils.isEmpty(override.getSchemas())
                ? new ArrayList<>(override.getSchemas())
                : (base.getSchemas() != null ? new ArrayList<>(base.getSchemas()) : null));
        merged.setDefaultSchema(firstNonBlank(override != null ? override.getDefaultSchema() : null, base.getDefaultSchema()));
        merged.setBaselineOnMigrate(override != null && override.getBaselineOnMigrate() != null
                ? override.getBaselineOnMigrate() : base.getBaselineOnMigrate());
        merged.setBaselineVersion(firstNonBlank(override != null ? override.getBaselineVersion() : null, base.getBaselineVersion()));
        merged.setBaselineDescription(firstNonBlank(
                override != null ? override.getBaselineDescription() : null, base.getBaselineDescription()));
        merged.setValidateOnMigrate(override != null && override.getValidateOnMigrate() != null
                ? override.getValidateOnMigrate() : base.getValidateOnMigrate());
        merged.setOutOfOrder(override != null && override.getOutOfOrder() != null
                ? override.getOutOfOrder() : base.getOutOfOrder());
        merged.setEncoding(override != null && override.getEncoding() != null ? override.getEncoding() : base.getEncoding());
        if (!CollectionUtils.isEmpty(base.getPlaceholders())) {
            merged.getPlaceholders().putAll(base.getPlaceholders());
        }
        if (override != null && !CollectionUtils.isEmpty(override.getPlaceholders())) {
            merged.getPlaceholders().putAll(override.getPlaceholders());
        }
        return merged;
    }

    private String firstNonBlank(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary : fallback;
    }

    private Map<String, DataSource> safePhysicalMap(DynamicRoutingDataSource routingDataSource) {
        Map<String, DataSource> map = routingDataSource.getDataSources();
        return map != null ? map : Collections.emptyMap();
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
        boolean skipSlave = properties.getRouting() == null || properties.getRouting().isSkipSlave();
        boolean skipReadOnlyGroup = properties.getRouting() == null || properties.getRouting().isSkipReadOnlyGroup();
        String configuredReadWriteSlave = resolveConfiguredReadWriteSlave();
        if (CollectionUtils.isEmpty(groupMap) && (!skipSlave || !StringUtils.hasText(configuredReadWriteSlave))) {
            return Collections.emptySet();
        }
        Set<String> excluded = new LinkedHashSet<>();
        if (skipSlave && StringUtils.hasText(configuredReadWriteSlave)) {
            excluded.add(configuredReadWriteSlave);
        }
        if (!skipReadOnlyGroup || CollectionUtils.isEmpty(groupMap)) {
            return excluded;
        }
        for (Map.Entry<String, GroupDataSource> entry : groupMap.entrySet()) {
            String groupName = entry.getKey();
            GroupDataSource group = entry.getValue();
            if (!StringUtils.hasText(groupName)) {
                continue;
            }
            String normalizedGroup = groupName.trim().toLowerCase(Locale.ROOT);
            boolean configuredSlaveGroup = skipSlave && Objects.equals(normalizedGroup, configuredReadWriteSlave);
            boolean readOnlyGroup = configuredSlaveGroup
                    || TargetRoutingSupport.isReadOnlyGroupNaming(normalizedGroup)
                    || areAllMembersSlaveNamed(group);
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

    private String resolveConfiguredReadWriteSlave() {
        if (datasourceProperties != null && datasourceProperties.getReadWriteSplit() != null
                && StringUtils.hasText(datasourceProperties.getReadWriteSplit().getSlaveDataSource())) {
            return datasourceProperties.getReadWriteSplit().getSlaveDataSource().trim().toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private boolean areAllMembersSlaveNamed(GroupDataSource group) {
        if (group == null || CollectionUtils.isEmpty(group.getDataSourceMap())) {
            return false;
        }
        for (String memberKey : group.getDataSourceMap().keySet()) {
            if (!TargetRoutingSupport.isSlaveNaming(memberKey)) {
                return false;
            }
        }
        return true;
    }

    private BatchExecutionPolicy resolveStaticPolicy() {
        return properties.getStaticFailurePolicy() != null
                ? properties.getStaticFailurePolicy()
                : BatchExecutionPolicy.FAIL_FAST;
    }

    private AtengFlywayProperties.CredentialProperties resolveCredential(
            AtengFlywayProperties.CredentialProperties baseCred,
            AtengFlywayProperties.CredentialProperties targetCred) {
        if (targetCred != null && targetCred.isConfigured()) {
            return targetCred;
        }
        return baseCred != null && baseCred.isConfigured() ? baseCred : null;
    }
}
