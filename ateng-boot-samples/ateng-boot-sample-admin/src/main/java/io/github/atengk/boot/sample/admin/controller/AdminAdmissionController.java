package io.github.atengk.boot.sample.admin.controller;

import io.github.atengk.boot.admin.admission.AdminAdmissionResult;
import io.github.atengk.boot.admin.admission.AdminInstanceAdmissionPolicy;
import io.github.atengk.boot.admin.admission.DefaultAdminInstanceAdmissionPolicy;
import io.github.atengk.boot.admin.admission.InstanceRegistration;
import io.github.atengk.boot.admin.autoconfigure.AtengAdminServerAutoConfiguration;
import io.github.atengk.boot.admin.provider.AdminAuthProvider;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.sample.admin.dto.AdmissionEvaluateRequest;
import io.github.atengk.boot.sample.admin.dto.AdmissionEvaluateVO;
import io.github.atengk.boot.sample.admin.dto.AuthVerifyRequest;
import io.github.atengk.boot.sample.admin.dto.AuthVerifyVO;
import io.github.atengk.boot.sample.admin.dto.ContextPathNormalizeVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.validation.Valid;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 实例准入评估与控制台认证领域控制器
 * 覆盖 Registration Admission Policy 评估、Console Authentication SPI 校验与路径规范化体验
 *
 * @author Ateng
 * @since 2026-11-11
 */
@RestController
@RequestMapping("/sample/admin/admission")
public class AdminAdmissionController {

    private final AdminInstanceAdmissionPolicy admissionPolicy;
    private final AdminAuthProvider authProvider;

    public AdminAdmissionController(AdminInstanceAdmissionPolicy admissionPolicy,
                                    AdminAuthProvider authProvider) {
        this.admissionPolicy = admissionPolicy;
        this.authProvider = authProvider;
    }

    /**
     * 预演评估实例注册快照准入结果（校验集群防伪密钥、http/https Scheme、云元数据与命名规范）
     *
     * @param request        准入预检请求参数
     * @param servletRequest 当前 HTTP Servlet 请求
     * @return 准入评估响应视图
     */
    @PostMapping("/evaluate")
    public Result<AdmissionEvaluateVO> evaluateAdmission(@Valid @RequestBody AdmissionEvaluateRequest request,
                                                         HttpServletRequest servletRequest) {
        InstanceRegistration registration = InstanceRegistration.builder()
                .name(request.getServiceName().trim())
                .serviceUrl(request.getServiceUrl().trim())
                .managementUrl(StringUtils.hasText(request.getManagementUrl()) ? request.getManagementUrl().trim() : null)
                .healthUrl(StringUtils.hasText(request.getHealthUrl()) ? request.getHealthUrl().trim() : null)
                .source("sample-http-preview")
                .build();

        HttpServletRequest evaluatedRequest = StringUtils.hasText(request.getClientSecret())
                ? new ClientSecretOverrideRequestWrapper(servletRequest, request.getClientSecret())
                : servletRequest;

        AdminAdmissionResult result = admissionPolicy.evaluate(registration, evaluatedRequest);
        AdmissionEvaluateVO vo = AdmissionEvaluateVO.builder()
                .allowed(result.isAllowed())
                .httpStatus(result.getStatus().value())
                .reason(result.getReason())
                .serviceName(registration.getName())
                .policyClass(admissionPolicy.getClass().getSimpleName())
                .build();
        return Result.success(vo);
    }

    /**
     * 演练控制台管理员认证 SPI（AdminAuthProvider），仅返回脱敏账号摘要
     *
     * @param request 登录认证演练请求参数
     * @return 认证结果与脱敏账号摘要
     */
    @PostMapping("/auth-verify")
    public Result<AuthVerifyVO> verifyAdminAuth(@Valid @RequestBody AuthVerifyRequest request) {
        boolean authenticated = authProvider.authenticate(request.getUsername(), request.getPassword());
        String username = request.getUsername();
        String maskedUser = username.length() <= 3
                ? DesensitizeUtil.mask(username, 1, username.length())
                : DesensitizeUtil.maskKeepHeadTail(username, 2, 1);
        AuthVerifyVO vo = AuthVerifyVO.builder()
                .authenticated(authenticated)
                .maskedUsername(maskedUser)
                .providerClass(authProvider.getClass().getSimpleName())
                .message(authenticated ? "控制台认证通过" : "账号或访问口令校验未通过")
                .build();
        return Result.success(vo);
    }

    /**
     * 演示 Spring Boot Admin Server context-path 规范化逻辑
     *
     * @param path 原始上下文路径字符串
     * @return 规范化前后路径对照视图
     */
    @GetMapping("/normalize-path")
    public Result<ContextPathNormalizeVO> normalizeContextPath(
            @RequestParam(value = "path", defaultValue = "") String path) {
        String normalized = AtengAdminServerAutoConfiguration.normalizeContextPath(path);
        return Result.success(ContextPathNormalizeVO.builder()
                .rawPath(path)
                .normalizedPath(normalized)
                .build());
    }

    private static final class ClientSecretOverrideRequestWrapper extends HttpServletRequestWrapper {

        private final String clientSecret;

        private ClientSecretOverrideRequestWrapper(HttpServletRequest request, String clientSecret) {
            super(request);
            this.clientSecret = clientSecret;
        }

        @Override
        public String getHeader(String name) {
            if (DefaultAdminInstanceAdmissionPolicy.CLIENT_SECRET_HEADER.equalsIgnoreCase(name)) {
                return clientSecret;
            }
            return super.getHeader(name);
        }
    }
}
