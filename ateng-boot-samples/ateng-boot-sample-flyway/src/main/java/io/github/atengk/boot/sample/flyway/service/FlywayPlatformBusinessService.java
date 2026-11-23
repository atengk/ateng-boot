package io.github.atengk.boot.sample.flyway.service;

import com.baomidou.dynamic.datasource.creator.DataSourceProperty;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.ValidatorUtil;
import io.github.atengk.boot.datasource.properties.DatasourceProperties;
import io.github.atengk.boot.datasource.template.DynamicDatasourceTemplate;
import io.github.atengk.boot.flyway.model.MigrationTargetInfo;
import io.github.atengk.boot.flyway.template.FlywayTemplate;
import io.github.atengk.boot.sample.flyway.dto.TenantMountRequest;
import io.github.atengk.boot.sample.flyway.dto.TenantOrderCreateRequest;
import io.github.atengk.boot.sample.flyway.entity.AuditLogEntity;
import io.github.atengk.boot.sample.flyway.entity.TenantOrderEntity;
import io.github.atengk.boot.sample.flyway.entity.TenantRegistryEntity;
import io.github.atengk.boot.sample.flyway.mapper.AuditLogMapper;
import io.github.atengk.boot.sample.flyway.mapper.TenantOrderMapper;
import io.github.atengk.boot.sample.flyway.mapper.TenantRegistryMapper;
import io.github.atengk.boot.sample.flyway.vo.AuditLogResponse;
import io.github.atengk.boot.sample.flyway.vo.TenantMountResponse;
import io.github.atengk.boot.sample.flyway.vo.TenantOrderResponse;
import io.github.atengk.boot.sample.flyway.vo.TenantRegistryResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.function.Function;

/**
 * 平台基础数据、独立审计库与 SaaS 物理多租户业务服务
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlywayPlatformBusinessService {

    private static final String TENANT_PREFIX = "tenant_";

    private final TenantRegistryMapper tenantRegistryMapper;
    private final AuditLogMapper auditLogMapper;
    private final TenantOrderMapper tenantOrderMapper;
    private final DynamicDatasourceTemplate dynamicDatasourceTemplate;
    private final DatasourceProperties datasourceProperties;
    private final FlywayTemplate flywayTemplate;

    /**
     * 查询 master 主库中由 Flyway 迁移并预置的平台租户注册清单
     *
     * @return 租户注册视图列表（永不为 null）
     */
    public List<TenantRegistryResponse> listPlatformTenants() {
        List<TenantRegistryEntity> entities = tenantRegistryMapper.selectList(
                new LambdaQueryWrapper<TenantRegistryEntity>().orderByAsc(TenantRegistryEntity::getId));
        return mapNonEmptyList(entities, entity -> TenantRegistryResponse.builder()
                .id(entity.getId())
                .tenantCode(entity.getTenantCode())
                .tenantName(entity.getTenantName())
                .dsKey(entity.getDsKey())
                .planTier(entity.getPlanTier())
                .sourceDs(entity.getSourceDs())
                .status(entity.getStatus())
                .build());
    }

    /**
     * 查询 log_db 独立业务库中的审计日志清单
     *
     * @return 审计日志视图列表（永不为 null）
     */
    public List<AuditLogResponse> listAuditLogs() {
        return dynamicDatasourceTemplate.callInDataSource("log_db", () -> {
            List<AuditLogEntity> entities = auditLogMapper.selectList(
                    new LambdaQueryWrapper<AuditLogEntity>().orderByAsc(AuditLogEntity::getId));
            return mapNonEmptyList(entities, entity -> AuditLogResponse.builder()
                    .id(entity.getId())
                    .sourceDs(entity.getSourceDs())
                    .auditScope(entity.getAuditScope())
                    .actionName(entity.getActionName())
                    .operatorName(entity.getOperatorName())
                    .detail(entity.getDetail())
                    .build());
        });
    }

    /**
     * 运行期动态挂载新物理租户数据源并验证自动建表迁移结果
     *
     * @param request 动态挂载请求
     * @return 挂载与自动迁移汇总视图
     */
    public TenantMountResponse mountTenantDataSource(TenantMountRequest request) {
        BusinessAssert.notNull(request, "挂载请求不能为空");
        ValidatorUtil.validate(request);

        String tenantId = request.getTenantId().trim();
        String dsKey = resolveTenantPrefix() + tenantId;
        boolean newlyMounted = false;

        if (!dynamicDatasourceTemplate.containsDataSource(dsKey)) {
            DataSourceProperty property = buildTenantDataSourceProperty(dsKey, request);
            dynamicDatasourceTemplate.addDataSource(dsKey, property, false);
            newlyMounted = true;
            log.info("[SampleFlyway] 动态挂载租户数据源成功并已触发 Flyway 自动建表 [dsKey={}]", dsKey);
        }

        try {
            registerTenantMetadataIfAbsent(tenantId, dsKey, request.getTenantName());
        } catch (RuntimeException ex) {
            if (newlyMounted) {
                try {
                    dynamicDatasourceTemplate.removeDataSource(dsKey, true);
                } catch (Exception rollbackEx) {
                    ex.addSuppressed(rollbackEx);
                }
            }
            throw ex;
        }
        MigrationTargetInfo targetInfo = flywayTemplate.info(dsKey);
        List<TenantOrderResponse> orders = listTenantOrders(tenantId);

        return TenantMountResponse.builder()
                .tenantId(tenantId)
                .dsKey(dsKey)
                .mounted(dynamicDatasourceTemplate.containsDataSource(dsKey))
                .currentVersion(targetInfo.getCurrentVersion())
                .appliedCount(targetInfo.getAppliedCount())
                .initialOrderCount(orders.size())
                .build();
    }

    /**
     * 在指定租户物理分库写入订单（验证启动/动态迁移后 MyBatis-Plus 立即可读写）
     *
     * @param request 订单创建请求
     * @return 订单响应视图
     */
    public TenantOrderResponse createTenantOrder(TenantOrderCreateRequest request) {
        BusinessAssert.notNull(request, "创建订单请求不能为空");
        ValidatorUtil.validate(request);

        String tenantId = request.getTenantId().trim();
        String dsKey = resolveTenantPrefix() + tenantId;
        return dynamicDatasourceTemplate.callInTenantDataSource(tenantId, () -> {
            TenantOrderEntity entity = new TenantOrderEntity();
            entity.setOrderNo(request.getOrderNo().trim());
            entity.setTenantId(tenantId);
            entity.setSourceDs(dsKey);
            entity.setProductName(request.getProductName().trim());
            entity.setAmount(request.getAmount());
            entity.setStatus("CREATED");
            tenantOrderMapper.insert(entity);

            return toOrderResponse(entity, dynamicDatasourceTemplate.getCurrentDataSourceKey());
        });
    }

    /**
     * 查询指定租户物理分库中的全部订单（验证跨租户物理隔离）
     *
     * @param tenantId 租户标识
     * @return 订单响应视图列表（永不为 null）
     */
    public List<TenantOrderResponse> listTenantOrders(String tenantId) {
        BusinessAssert.hasText(tenantId, "租户标识 (tenantId) 不能为空");
        String normalizedTenant = tenantId.trim();
        return dynamicDatasourceTemplate.callInTenantDataSource(normalizedTenant, () -> {
            String routedDs = dynamicDatasourceTemplate.getCurrentDataSourceKey();
            List<TenantOrderEntity> entities = tenantOrderMapper.selectList(
                    new LambdaQueryWrapper<TenantOrderEntity>().orderByAsc(TenantOrderEntity::getId));
            return mapNonEmptyList(entities, entity -> toOrderResponse(entity, routedDs));
        });
    }

    private <E, R> List<R> mapNonEmptyList(List<E> entities, Function<E, R> mapper) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        return entities.stream().map(mapper).toList();
    }

    private String resolveTenantPrefix() {
        if (datasourceProperties != null && datasourceProperties.getMultiTenancy() != null
                && StringUtils.hasText(datasourceProperties.getMultiTenancy().getDataSourcePrefix())) {
            return datasourceProperties.getMultiTenancy().getDataSourcePrefix().trim();
        }
        return TENANT_PREFIX;
    }

    private DataSourceProperty buildTenantDataSourceProperty(String dsKey,
                                                             TenantMountRequest request) {
        DataSourceProperty property = new DataSourceProperty();
        property.setPoolName("sample-dynamic-" + dsKey);
        String jdbcUrl = StringUtils.hasText(request.getJdbcUrl())
                ? request.getJdbcUrl().trim()
                : "jdbc:h2:mem:flyway_sample_" + dsKey + "_" + IdUtil.fastSimpleUUID()
                + ";DB_CLOSE_DELAY=-1;MODE=MySQL";
        property.setUrl(jdbcUrl);
        property.setUsername(StringUtils.hasText(request.getUsername()) ? request.getUsername().trim() : "sa");
        property.setPassword(request.getPassword() != null ? request.getPassword() : "");
        property.setDriverClassName(StringUtils.hasText(request.getDriverClassName())
                ? request.getDriverClassName().trim()
                : "org.h2.Driver");
        return property;
    }

    private void registerTenantMetadataIfAbsent(String tenantId, String dsKey, String tenantName) {
        Long count = tenantRegistryMapper.selectCount(
                new LambdaQueryWrapper<TenantRegistryEntity>().eq(TenantRegistryEntity::getTenantCode, tenantId));
        if (count != null && count > 0) {
            return;
        }
        TenantRegistryEntity registry = new TenantRegistryEntity();
        registry.setTenantCode(tenantId);
        registry.setTenantName(StringUtils.hasText(tenantName) ? tenantName.trim() : "动态接入租户-" + tenantId);
        registry.setDsKey(dsKey);
        registry.setPlanTier("STANDARD");
        registry.setSourceDs("master");
        registry.setStatus("ACTIVE");
        tenantRegistryMapper.insert(registry);
    }

    private TenantOrderResponse toOrderResponse(TenantOrderEntity entity, String routedDs) {
        return TenantOrderResponse.builder()
                .id(entity.getId())
                .orderNo(entity.getOrderNo())
                .tenantId(entity.getTenantId())
                .sourceDs(entity.getSourceDs())
                .productName(entity.getProductName())
                .amount(entity.getAmount())
                .status(entity.getStatus())
                .routedDataSource(routedDs)
                .build();
    }
}
