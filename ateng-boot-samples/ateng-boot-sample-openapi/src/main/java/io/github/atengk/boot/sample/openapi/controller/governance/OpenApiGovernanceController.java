package io.github.atengk.boot.sample.openapi.controller.governance;

import cn.dev33.satoken.annotation.SaIgnore;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.code.IResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.FileUtil;
import io.github.atengk.boot.openapi.export.OpenApiExportHelper;
import io.github.atengk.boot.openapi.export.OpenApiFormat;
import io.github.atengk.boot.openapi.spi.ResultCodeProvider;
import io.github.atengk.boot.sample.openapi.model.ContractExportRequest;
import io.github.atengk.boot.sample.openapi.model.ContractExportResultVO;
import io.github.atengk.boot.sample.openapi.model.ContractPreviewVO;
import io.github.atengk.boot.sample.openapi.model.ResultCodeItemVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.OrderComparator;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 契约治理与离线导出控制器
 * <p>
 * 提供 OpenAPI 契约实时预览、隔离临时目录安全写盘校验与系统/业务状态码全景字典检索接口，
 * 端到端实证 {@link OpenApiExportHelper} 与 {@link ResultCodeProvider} SPI 能力。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-16
 */
@Tag(name = "契约治理与导出", description = "OpenAPI 契约摘要预览、安全落盘验证与状态码全景字典检索接口")
@Validated
@RestController
@RequestMapping("/sample/openapi/governance")
public class OpenApiGovernanceController {

    private static final int PREVIEW_SNIPPET_LIMIT = 512;
    private static final String SAMPLE_TEMP_APP_NAME = "ateng-boot-sample-openapi";

    private final ObjectProvider<OpenApiExportHelper> exportHelperProvider;
    private final List<ResultCodeProvider> resultCodeProviders;

    /**
     * 构造契约治理控制器
     *
     * @param exportHelperProvider 离线契约导出门面延迟提供者（支持 openapi.enabled=false 场景优雅降级）
     * @param resultCodeProviders  业务状态码扩展提供者列表
     */
    public OpenApiGovernanceController(ObjectProvider<OpenApiExportHelper> exportHelperProvider,
                                       List<ResultCodeProvider> resultCodeProviders) {
        this.exportHelperProvider = Objects.requireNonNull(exportHelperProvider, "exportHelperProvider must not be null");
        List<ResultCodeProvider> sorted = (resultCodeProviders != null)
                ? new ArrayList<>(resultCodeProviders)
                : new ArrayList<>();
        OrderComparator.sort(sorted);
        this.resultCodeProviders = Collections.unmodifiableList(sorted);
    }

    /**
     * 预览指定分组的 OpenAPI 契约摘要
     *
     * @param group   业务分组标识（可选，默认解析 default 分组）
     * @param format  目标格式（JSON / YAML）
     * @param request 当前 HTTP 请求
     * @return 契约摘要预览视图
     */
    @Operation(summary = "契约摘要预览", description = "调用 OpenApiExportHelper 导出指定分组规范并返回长度与内容前缀摘要")
    @SaIgnore
    @GetMapping("/preview")
    public Result<ContractPreviewVO> previewContract(
            @Parameter(description = "业务分组标识（留空默认解析 default 分组）", example = "admin")
            @RequestParam(value = "group", required = false) String group,
            @Parameter(description = "导出格式", example = "JSON")
            @RequestParam(value = "format", defaultValue = "JSON") OpenApiFormat format,
            HttpServletRequest request) {
        String normalizedGroup = normalizeAndValidateGroup(group, true);
        OpenApiExportHelper helper = requireExportHelper();
        try {
            byte[] bytes = helper.exportBytes(normalizedGroup, format, request);
            String fullText = new String(bytes, StandardCharsets.UTF_8);
            String snippet = fullText.length() > PREVIEW_SNIPPET_LIMIT
                    ? fullText.substring(0, PREVIEW_SNIPPET_LIMIT)
                    : fullText;
            String effectiveGroup = normalizedGroup != null ? normalizedGroup : "default";
            return Result.success(new ContractPreviewVO(effectiveGroup, format, bytes.length, snippet));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    /**
     * 安全导出契约至应用受管临时目录并校验路径安全
     *
     * @param request     导出请求参数
     * @param httpRequest 当前 HTTP 请求
     * @return 落盘结果元数据
     */
    @Operation(summary = "安全写盘校验", description = "验证路径防穿越并调用 OpenApiExportHelper.exportFile 安全写入应用受管临时目录")
    @SaIgnore
    @PostMapping("/export-check")
    public Result<ContractExportResultVO> exportContractSafely(@Valid @RequestBody ContractExportRequest request,
                                                               HttpServletRequest httpRequest) {
        String normalizedGroup = normalizeAndValidateGroup(request.getGroup(), false);
        OpenApiExportHelper helper = requireExportHelper();
        try {
            Path appTempDir = FileUtil.getAppTempDirectory(SAMPLE_TEMP_APP_NAME).toAbsolutePath().normalize();
            Path targetFile = FileUtil.resolveSafePath(appTempDir, request.getRelativePath());
            Path written = helper.exportFile(normalizedGroup, request.getFormat(), targetFile, httpRequest);
            long size = Files.size(written);
            String relativeDisplay = appTempDir.relativize(written).toString().replace('\\', '/');
            ContractExportResultVO result = new ContractExportResultVO(
                    normalizedGroup,
                    request.getFormat(),
                    relativeDisplay,
                    size,
                    FileUtil.formatSize(size)
            );
            return Result.success(result);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "非法导出路径或分组参数: " + request.getRelativePath(), ex);
        } catch (IOException ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "导出契约落盘失败: " + request.getRelativePath(), ex);
        }
    }

    private OpenApiExportHelper requireExportHelper() {
        OpenApiExportHelper helper = exportHelperProvider.getIfAvailable();
        if (helper == null) {
            throw new BusinessException(CommonResultCode.SERVICE_UNAVAILABLE, "OpenAPI 导出能力未启用或尚未装配");
        }
        return helper;
    }

    /**
     * 检索系统通用与 SPI 扩展状态码全景字典
     *
     * @param keyword 过滤关键字（支持按状态码数值、消息或场景模糊匹配，可选）
     * @return 匹配的状态码条目列表（无匹配保底返回空列表）
     */
    @Operation(summary = "状态码字典检索", description = "聚合 Core CommonResultCode 与全量 ResultCodeProvider SPI 扩展状态码并支持关键字筛选")
    @SaIgnore
    @GetMapping("/result-codes")
    public Result<List<ResultCodeItemVO>> listResultCodes(
            @Parameter(description = "模糊过滤关键字（状态码或说明）", example = "8001")
            @RequestParam(value = "keyword", required = false) String keyword) {
        List<ResultCodeItemVO> items = new ArrayList<>();
        String filter = StringUtils.hasText(keyword) ? keyword.trim().toLowerCase(Locale.ROOT) : null;

        for (CommonResultCode commonCode : CommonResultCode.values()) {
            ResultCodeItemVO item = new ResultCodeItemVO(
                    commonCode.getCode(),
                    commonCode.getMsg(),
                    "CommonResultCode",
                    commonCode.getMsg()
            );
            if (matchesKeyword(item, filter)) {
                items.add(item);
            }
        }

        for (ResultCodeProvider provider : resultCodeProviders) {
            if (provider == null) {
                continue;
            }
            Collection<? extends IResultCode> codes = provider.getResultCodes();
            if (codes == null || codes.isEmpty()) {
                continue;
            }
            String source = StringUtils.hasText(provider.getTitle())
                    ? provider.getTitle().replaceFirst("^#+\\s*", "").trim()
                    : provider.getClass().getSimpleName();
            for (IResultCode rc : codes) {
                if (rc == null || rc.getCode() == null) {
                    continue;
                }
                String scenario = StringUtils.hasText(provider.resolveScenario(rc))
                        ? provider.resolveScenario(rc)
                        : rc.getMsg();
                ResultCodeItemVO item = new ResultCodeItemVO(rc.getCode(), rc.getMsg(), source, scenario);
                if (matchesKeyword(item, filter)) {
                    items.add(item);
                }
            }
        }

        return Result.success(items);
    }

    private static String normalizeAndValidateGroup(String group, boolean allowBlank) {
        if (!StringUtils.hasText(group)) {
            if (allowBlank) {
                return null;
            }
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "分组名称不能为空");
        }
        String trimmed = group.trim();
        if (!OpenApiExportHelper.isValidGroupName(trimmed)) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "非法的业务分组标识: " + trimmed);
        }
        return trimmed;
    }

    private static boolean matchesKeyword(ResultCodeItemVO item, String normalizedKeyword) {
        if (normalizedKeyword == null) {
            return true;
        }
        String codeText = item.getCode() != null ? String.valueOf(item.getCode()) : "";
        String msgText = item.getMsg() != null ? item.getMsg().toLowerCase(Locale.ROOT) : "";
        String sourceText = item.getSource() != null ? item.getSource().toLowerCase(Locale.ROOT) : "";
        String scenarioText = item.getScenario() != null ? item.getScenario().toLowerCase(Locale.ROOT) : "";
        return codeText.contains(normalizedKeyword)
                || msgText.contains(normalizedKeyword)
                || sourceText.contains(normalizedKeyword)
                || scenarioText.contains(normalizedKeyword);
    }
}
