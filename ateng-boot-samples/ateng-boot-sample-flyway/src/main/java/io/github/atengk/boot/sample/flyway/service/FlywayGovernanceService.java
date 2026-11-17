package io.github.atengk.boot.sample.flyway.service;

import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.core.util.CryptoUtil;
import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.core.util.ValidatorUtil;
import io.github.atengk.boot.flyway.enums.FlywayResultCode;
import io.github.atengk.boot.flyway.exception.FlywayException;
import io.github.atengk.boot.flyway.model.MigrationTarget;
import io.github.atengk.boot.flyway.observability.FlywayHealthIndicator;
import io.github.atengk.boot.flyway.properties.AtengFlywayProperties;
import io.github.atengk.boot.flyway.spi.FlywayTargetResolver;
import io.github.atengk.boot.flyway.template.FlywayTemplate;
import io.github.atengk.boot.sample.flyway.dto.IsolatedCredentialDemoRequest;
import io.github.atengk.boot.sample.flyway.vo.IsolatedCredentialDemoResponse;
import io.github.atengk.boot.sample.flyway.vo.MigrationHealthResponse;
import lombok.RequiredArgsConstructor;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.ValidateResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 安全审计、隔离式加密 DDL 凭据演练与迁移健康探针服务
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Service
@RequiredArgsConstructor
public class FlywayGovernanceService {

    private final FlywayTargetResolver targetResolver;
    private final FlywayTemplate flywayTemplate;
    private final ObjectProvider<FlywayHealthIndicator> healthIndicatorProvider;

    /**
     * 演练使用 SM4 密文口令构建短生命周期瞬态 DDL 连接执行校验并在结束后确定性关闭连接池
     *
     * @param request 隔离凭据演练请求
     * @return 脱敏后的瞬态执行回执
     */
    public IsolatedCredentialDemoResponse demonstrateIsolatedCredential(IsolatedCredentialDemoRequest request) {
        BusinessAssert.notNull(request, "隔离凭据演练请求不能为空");
        ValidatorUtil.validate(request);

        String targetKey = request.getTargetKey().trim();
        MigrationTarget baseTarget = targetResolver.resolveTarget(targetKey);
        if (baseTarget == null) {
            throw new FlywayException(FlywayResultCode.TARGET_NOT_FOUND,
                    "未找到受管的迁移目标数据源: [" + targetKey + "]");
        }

        String plainPwd = request.getPlainPassword() != null ? request.getPlainPassword() : "";
        byte[] sm4Key = CryptoUtil.generateSm4Key();
        byte[] sm4Iv = CryptoUtil.generateIv(CryptoUtil.SM4_IV_SIZE);
        String encryptedPassword = "enc:sm4:"
                + CryptoUtil.encodeBase64(sm4Key) + ":"
                + CryptoUtil.encodeBase64(sm4Iv) + ":"
                + CryptoUtil.encryptSm4Cbc(plainPwd, sm4Key, sm4Iv);

        AtengFlywayProperties.CredentialProperties transientCred = new AtengFlywayProperties.CredentialProperties();
        transientCred.setEnabled(true);
        transientCred.setUsername(request.getUsername().trim());
        transientCred.setPassword(encryptedPassword);

        MigrationTarget credTarget = MigrationTarget.builder()
                .targetKey(baseTarget.getTargetKey())
                .kind(baseTarget.getKind())
                .tenantId(baseTarget.getTenantId())
                .dataSource(baseTarget.getDataSource())
                .credential(transientCred)
                .migrationConfig(baseTarget.getMigrationConfig())
                .failurePolicy(baseTarget.getFailurePolicy())
                .build();

        ValidateResult result = flywayTemplate.executeTransient(credTarget, Flyway::validateWithResult);
        return IsolatedCredentialDemoResponse.builder()
                .targetKey(targetKey)
                .maskedCiphertext("enc:sm4:" + DesensitizeUtil.password(encryptedPassword))
                .validationPassed(result.validationSuccessful)
                .validateCount(result.validateCount)
                .activeTransientPoolCount(flywayTemplate.getActiveTransientPoolCount())
                .closedTransientPoolCount(flywayTemplate.getClosedTransientPoolCount())
                .build();
    }

    /**
     * 查询 {@link FlywayHealthIndicator} 缓存感知迁移健康快照
     *
     * @return 健康快照视图
     */
    public MigrationHealthResponse queryMigrationHealth() {
        FlywayHealthIndicator indicator = healthIndicatorProvider.getIfAvailable();
        if (indicator == null) {
            return MigrationHealthResponse.builder()
                    .status("UNKNOWN")
                    .details(Collections.emptyMap())
                    .databaseProbeCount(0L)
                    .build();
        }
        Health health = indicator.health();
        Map<String, Object> details = new LinkedHashMap<>(health.getDetails());
        return MigrationHealthResponse.builder()
                .status(health.getStatus().getCode())
                .details(Collections.unmodifiableMap(details))
                .databaseProbeCount(indicator.getDatabaseProbeCount())
                .build();
    }
}
