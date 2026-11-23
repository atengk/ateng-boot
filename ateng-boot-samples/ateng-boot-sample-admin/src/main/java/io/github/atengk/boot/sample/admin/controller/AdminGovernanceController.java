package io.github.atengk.boot.sample.admin.controller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import de.codecentric.boot.admin.server.config.AdminServerProperties;
import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.domain.values.Registration;
import io.github.atengk.boot.admin.autoconfigure.AtengAdminServerAutoConfiguration;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import io.github.atengk.boot.admin.provider.AtengActuatorHttpHeadersProvider;
import io.github.atengk.boot.admin.security.AdminWriteGuardFilter;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.sample.admin.dto.HeaderDerivePreviewRequest;
import io.github.atengk.boot.sample.admin.dto.HeaderDerivePreviewVO;
import io.github.atengk.boot.sample.admin.dto.LogLevelUpdateRequest;
import io.github.atengk.boot.sample.admin.dto.LoggerLevelVO;
import io.github.atengk.boot.sample.admin.dto.WriteGuardStatusVO;
import io.github.atengk.boot.sample.admin.dto.WriteGuardToggleRequest;
import io.github.atengk.boot.sample.admin.support.SamplePropertyOverrideSupport;
import jakarta.validation.Valid;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 控制台只读治理、出站凭据级联推导与在线日志管控领域控制器
 *
 * @author Ateng
 * @since 2026-11-11
 */
@RestController
@RequestMapping("/sample/admin/governance")
public class AdminGovernanceController {

    private static final String DEFAULT_PROBE_METHOD = "POST";
    private static final String DEFAULT_PROBE_URI = "/admin/instances/order-core-01/actuator/loggers/root";

    private final AtengAdminServerProperties properties;
    private final AdminWriteGuardFilter writeGuardFilter;
    private final AtengActuatorHttpHeadersProvider headersProvider;
    private final String contextPath;
    private final ReentrantLock writeGuardLock = new ReentrantLock();

    public AdminGovernanceController(AtengAdminServerProperties properties,
                                     AdminWriteGuardFilter writeGuardFilter,
                                     AtengActuatorHttpHeadersProvider headersProvider,
                                     ObjectProvider<AdminServerProperties> adminServerPropertiesProvider) {
        this.properties = properties;
        this.writeGuardFilter = writeGuardFilter;
        this.headersProvider = headersProvider;
        AdminServerProperties adminServerProperties = adminServerPropertiesProvider.getIfAvailable();
        this.contextPath = AtengAdminServerAutoConfiguration.normalizeContextPath(
                adminServerProperties != null ? adminServerProperties.getContextPath() : null
        );
    }

    /**
     * 查询当前控制台只读写守卫策略与指定代理路径的拦截判定
     *
     * @param probeMethod 待探测 HTTP 方法（默认 POST）
     * @param probeUri    待探测请求路径
     * @return 写守卫状态与阻断预判结果
     */
    @GetMapping("/write-guard")
    public Result<WriteGuardStatusVO> getWriteGuardStatus(
            @RequestParam(value = "method", defaultValue = DEFAULT_PROBE_METHOD) String probeMethod,
            @RequestParam(value = "uri", defaultValue = DEFAULT_PROBE_URI) String probeUri) {
        return SamplePropertyOverrideSupport.withLock(writeGuardLock,
                () -> Result.success(buildWriteGuardStatus(probeMethod, probeUri)));
    }

    /**
     * 动态切换单机沙箱演示环境的 Console Write Guard 只读开关并即时返回拦截预判结果
     *
     * @param request 只读写守卫切换与探测参数
     * @return 更新后的写守卫状态与阻断预判结果
     */
    @PostMapping("/write-guard")
    public Result<WriteGuardStatusVO> toggleWriteGuard(@Valid @RequestBody WriteGuardToggleRequest request) {
        String method = StringUtils.hasText(request.getProbeMethod()) ? request.getProbeMethod().trim() : DEFAULT_PROBE_METHOD;
        String uri = StringUtils.hasText(request.getProbeUri()) ? request.getProbeUri().trim() : DEFAULT_PROBE_URI;
        return SamplePropertyOverrideSupport.withLock(writeGuardLock, () -> {
            // 仅用于单节点 Sample 沙箱实时体验 AdminWriteGuardFilter 403 拦截与放行切换
            properties.setEnableWriteOperations(request.getEnableWriteOperations());
            return Result.success(buildWriteGuardStatus(method, uri));
        });
    }

    /**
     * 预览 Outbound Actuator Credential Cascade 四阶出站抓取标头推导结果（严格脱敏回显，不泄露明文密钥）
     *
     * @param request 出站标头推导预览参数
     * @return 匹配认证层级、标头名称与脱敏标头值
     */
    @PostMapping("/headers/preview")
    public Result<HeaderDerivePreviewVO> previewOutboundHeaders(@Valid @RequestBody HeaderDerivePreviewRequest request) {
        Registration.Builder regBuilder = Registration.builder()
                .name(request.getServiceName().trim())
                .serviceUrl("http://127.0.0.1:8080/")
                .managementUrl("http://127.0.0.1:8080/actuator")
                .healthUrl("http://127.0.0.1:8080/actuator/health");
        if (request.getMetadata() != null && !request.getMetadata().isEmpty()) {
            regBuilder.metadata(request.getMetadata());
        }
        Instance transientInstance = Instance.create(InstanceId.of("preview-only")).register(regBuilder.build());
        HttpHeaders derived = headersProvider.getHeaders(transientInstance);

        return Result.success(buildMaskedHeaderPreview(request.getServiceName().trim(), request.getMetadata(), derived));
    }

    /**
     * 查询指定包或 Logger 的当前 Logback 日志级别
     *
     * @param loggerName 目标 Logger 名称
     * @return 配置级别与生效级别视图
     */
    @GetMapping("/loggers")
    public Result<LoggerLevelVO> getLoggerLevel(
            @RequestParam(value = "loggerName", defaultValue = "io.github.atengk.boot.sample.admin") String loggerName) {
        BusinessAssert.isTrue(StringUtils.hasText(loggerName), "Logger 名称不能为空");
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger logger = loggerContext.getLogger(loggerName.trim());
        return Result.success(toLoggerLevelVO(logger));
    }

    /**
     * 运行期在线调整指定 Logback Logger 日志级别
     *
     * @param request 日志级别更新请求参数
     * @return 调整后的日志级别视图
     */
    @PostMapping("/loggers")
    public Result<LoggerLevelVO> updateLoggerLevel(@Valid @RequestBody LogLevelUpdateRequest request) {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger logger = loggerContext.getLogger(request.getLoggerName().trim());
        logger.setLevel(Level.valueOf(request.getLevel().trim().toUpperCase(Locale.ROOT)));
        return Result.success(toLoggerLevelVO(logger));
    }

    private WriteGuardStatusVO buildWriteGuardStatus(String probeMethod, String probeUri) {
        String normalizedMethod = probeMethod.toUpperCase(Locale.ROOT);
        boolean proxyEndpoint = writeGuardFilter.isActuatorProxyEndpoint(probeUri);
        boolean wouldBlock = writeGuardFilter.shouldBlock(normalizedMethod, probeUri);
        return WriteGuardStatusVO.builder()
                .enableWriteOperations(properties.isEnableWriteOperations())
                .contextPath(contextPath)
                .probeMethod(normalizedMethod)
                .probeUri(probeUri)
                .actuatorProxyEndpoint(proxyEndpoint)
                .wouldBlock(wouldBlock)
                .build();
    }

    private HeaderDerivePreviewVO buildMaskedHeaderPreview(String serviceName,
                                                           Map<String, String> metadata,
                                                           HttpHeaders derived) {
        String authorization = derived.getFirst(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(authorization)) {
            if (authorization.startsWith("Basic ")) {
                return HeaderDerivePreviewVO.builder()
                        .serviceName(serviceName)
                        .authMode("BASIC")
                        .headerName(HttpHeaders.AUTHORIZATION)
                        .maskedHeaderValue("Basic " + DesensitizeUtil.password(authorization.substring(6), "******"))
                        .headerPresent(true)
                        .build();
            }
            if (authorization.startsWith("Bearer ")) {
                String rawBearer = authorization.substring(7);
                return HeaderDerivePreviewVO.builder()
                        .serviceName(serviceName)
                        .authMode("BEARER")
                        .headerName(HttpHeaders.AUTHORIZATION)
                        .maskedHeaderValue("Bearer " + maskCredential(rawBearer))
                        .headerPresent(true)
                        .build();
            }
        }

        String actuatorToken = derived.getFirst(AtengActuatorHttpHeadersProvider.ACTUATOR_TOKEN_HEADER);
        if (StringUtils.hasText(actuatorToken)) {
            boolean fromMetadata = metadata != null
                    && StringUtils.hasText(metadata.get(AtengActuatorHttpHeadersProvider.METADATA_ACTUATOR_TOKEN));
            return HeaderDerivePreviewVO.builder()
                    .serviceName(serviceName)
                    .authMode(fromMetadata ? "ACTUATOR_TOKEN" : "GLOBAL_FALLBACK")
                    .headerName(AtengActuatorHttpHeadersProvider.ACTUATOR_TOKEN_HEADER)
                    .maskedHeaderValue(maskCredential(actuatorToken))
                    .headerPresent(true)
                    .build();
        }

        return HeaderDerivePreviewVO.builder()
                .serviceName(serviceName)
                .authMode("NONE")
                .headerName(null)
                .maskedHeaderValue(null)
                .headerPresent(false)
                .build();
    }

    private String maskCredential(String rawToken) {
        if (!StringUtils.hasText(rawToken)) {
            return "";
        }
        return DesensitizeUtil.secretKey(rawToken);
    }

    private LoggerLevelVO toLoggerLevelVO(ch.qos.logback.classic.Logger logger) {
        return LoggerLevelVO.builder()
                .loggerName(logger.getName())
                .configuredLevel(logger.getLevel() != null ? logger.getLevel().levelStr : null)
                .effectiveLevel(logger.getEffectiveLevel().levelStr)
                .build();
    }
}
