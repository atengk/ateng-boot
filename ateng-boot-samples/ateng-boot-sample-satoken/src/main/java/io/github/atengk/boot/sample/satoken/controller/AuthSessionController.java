package io.github.atengk.boot.sample.satoken.controller;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.satoken.dto.LoginRequestDTO;
import io.github.atengk.boot.sample.satoken.dto.OrderCreateDTO;
import io.github.atengk.boot.sample.satoken.dto.TempTokenIssueDTO;
import io.github.atengk.boot.sample.satoken.service.SaTokenSampleService;
import io.github.atengk.boot.sample.satoken.vo.TempTokenTicketVO;
import io.github.atengk.boot.sample.satoken.vo.TempTokenVO;
import io.github.atengk.boot.sample.satoken.vo.TenantOrderVO;
import io.github.atengk.boot.satoken.annotation.Anonymous;
import io.github.atengk.boot.satoken.model.LoginUser;
import io.github.atengk.boot.satoken.util.LoginHelper;
import io.github.atengk.boot.satoken.util.TempTokenHelper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * 用户会话与租户上下文控制器
 * 提供多端登录、会话读取、租户上下文提取、设备注销与租户订单读写端点
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/satoken/auth")
@RequiredArgsConstructor
public class AuthSessionController {

    private final SaTokenSampleService sampleService;

    /**
     * 免鉴权健康探针接口（演示方法级 @Anonymous）
     *
     * @return 探针响应
     */
    @GetMapping("/public/ping")
    @Anonymous
    public Result<String> ping() {
        return Result.success("pong");
    }

    /**
     * 免鉴权公开文章接口（演示方法级 @Anonymous 支持路径变量通配）
     *
     * @param articleId 文章标识
     * @return 文章详情
     */
    @GetMapping("/public/article/{articleId}")
    @Anonymous
    public Result<String> getPublicArticle(@PathVariable("articleId") String articleId) {
        return Result.success("公开文章详情: " + articleId);
    }

    /**
     * 用户登录接口
     * 支持多端设备与多租户复合身份签发
     *
     * @param request 登录请求传输对象
     * @return 签发的访问凭据 Token
     */
    @PostMapping("/login")
    @Anonymous
    public Result<String> login(@RequestBody LoginRequestDTO request) {
        String userId = (request != null && request.getUserId() != null && !request.getUserId().isBlank())
                ? request.getUserId()
                : (request != null && request.getUsername() != null ? "user-" + request.getUsername() : "user-1001");
        String username = (request != null && request.getUsername() != null && !request.getUsername().isBlank())
                ? request.getUsername()
                : userId;

        LoginUser loginUser = LoginUser.builder()
                .userId(userId)
                .userAccount(username)
                .userName(username)
                .tenantId(request != null ? request.getTenantId() : null)
                .roles(request != null ? request.getRoles() : null)
                .permissions(request != null ? request.getPermissions() : null)
                .build();

        if (request != null && request.getDevice() != null && !request.getDevice().isBlank()) {
            LoginHelper.login(loginUser, request.getDevice());
        } else {
            LoginHelper.login(loginUser);
        }
        return Result.success(LoginHelper.getTokenValue());
    }

    /**
     * 校验登录状态并获取当前登录用户信息
     *
     * @return 当前登录主体
     */
    @GetMapping("/me")
    public Result<LoginUser> getLoginUser() {
        LoginHelper.checkLogin();
        return Result.success(LoginHelper.getLoginUser());
    }

    /**
     * 获取当前操作人用户 ID（透明剥离多租户前缀）
     *
     * @return 原始用户唯一标识
     */
    @GetMapping("/user-id")
    public Result<String> getUserId() {
        LoginHelper.checkLogin();
        return Result.success(LoginHelper.getUserId());
    }

    /**
     * 获取当前租户上下文标识
     *
     * @return 当前租户标识
     */
    @GetMapping("/current-tenant")
    public Result<String> getCurrentTenant() {
        LoginHelper.checkLogin();
        return Result.success(TenantContextHolder.getTenantId());
    }

    /**
     * 编程式校验登录状态
     *
     * @return 状态信息
     */
    @GetMapping("/check-login")
    public Result<String> checkLogin() {
        LoginHelper.checkLogin();
        return Result.success("已登录");
    }

    /**
     * 当前会话注销下线
     *
     * @return 成功响应
     */
    @PostMapping("/logout")
    public Result<Void> logout() {
        LoginHelper.logout();
        return Result.success();
    }

    /**
     * 指定设备端注销下线
     *
     * @param device 设备端类型标识（如 PC, APP, MINI_PROGRAM, H5）
     * @return 成功响应
     */
    @PostMapping("/logout-device")
    public Result<Void> logoutDevice(@RequestParam("device") String device) {
        LoginHelper.logoutByDevice(device);
        return Result.success();
    }

    /**
     * 创建租户采购订单（验证当前操作人与租户上下文自动审计填充）
     *
     * @param orderDto 订单创建传输对象
     * @return 订单持久化 ID
     */
    @PostMapping("/orders")
    public Result<Long> createOrder(@Valid @RequestBody OrderCreateDTO orderDto) {
        Long orderId = sampleService.createOrder(orderDto.getOrderNo(), orderDto.getAmount());
        return Result.success(orderId);
    }

    /**
     * 查询租户订单详情（验证行级多租户隔离与零实体泄露原则）
     *
     * @param orderId 订单标识
     * @return 订单展示视图对象
     */
    @GetMapping("/orders/{orderId}")
    public Result<TenantOrderVO> getOrder(@PathVariable("orderId") Long orderId) {
        TenantOrderVO order = sampleService.getOrderById(orderId);
        if (order == null) {
            return Result.fail(CommonResultCode.NOT_FOUND.getCode(), "订单不存在");
        }
        return Result.success(order);
    }

    /**
     * 主动续期当前登录会话 Token 剩余有效时间
     *
     * @param timeoutSeconds 新的有效时间（秒），默认 1800 秒
     * @param loginType      可选账号体系标识，默认主体系
     * @return 续期后的 Token 剩余有效时间（秒）
     */
    @PostMapping("/renew-timeout")
    public Result<Long> renewTimeout(
            @RequestParam(value = "timeoutSeconds", defaultValue = "1800")
            @Positive(message = "续期时间必须大于 0 秒") long timeoutSeconds,
            @RequestParam(value = "loginType", required = false) String loginType) {
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        if (loginType != null && !loginType.isBlank()) {
            LoginHelper.checkLogin(loginType);
            LoginHelper.renewTimeout(loginType, timeout);
            return Result.success(LoginHelper.getTokenTimeout(loginType));
        }
        LoginHelper.checkLogin();
        LoginHelper.renewTimeout(timeout);
        return Result.success(LoginHelper.getTokenTimeout());
    }

    /**
     * 签发强类型短效临时业务令牌（如限时文件下载签名、扫码确认、邮件激活票据）
     *
     * @param request 临时令牌签发请求传输对象
     * @return 签发后的临时令牌与载荷信息
     */
    @PostMapping("/temp-token/issue")
    public Result<TempTokenVO> issueTempToken(@Valid @RequestBody TempTokenIssueDTO request) {
        LoginHelper.checkLogin();
        long ttlSeconds = (request.getTimeoutSeconds() != null && request.getTimeoutSeconds() > 0)
                ? request.getTimeoutSeconds() : 300L;
        TempTokenTicketVO payload = TempTokenTicketVO.builder()
                .bizScene(request.getBizScene().trim())
                .resourceId(request.getResourceId().trim())
                .resourceName(request.getResourceName())
                .requesterId(LoginHelper.getUserId())
                .build();
        String tempToken = TempTokenHelper.createTempToken(payload, Duration.ofSeconds(ttlSeconds));
        long remainTtl = TempTokenHelper.getTempTokenTimeout(tempToken);
        return Result.success(TempTokenVO.builder()
                .token(tempToken)
                .ttlSeconds(remainTtl)
                .payload(payload)
                .build());
    }

    /**
     * 核验临时业务令牌并还原强类型业务载荷（支持匿名校验，如外链下载鉴权与激活链接校验）
     *
     * @param token 临时业务令牌凭据
     * @return 解析还原后的业务载荷与剩余有效期
     */
    @GetMapping("/temp-token/verify")
    @Anonymous
    public Result<TempTokenVO> verifyTempToken(
            @NotBlank(message = "临时令牌不能为空") @RequestParam("token") String token) {
        TempTokenTicketVO payload = TempTokenHelper.parseTempToken(token, TempTokenTicketVO.class);
        if (payload == null) {
            return Result.fail(CommonResultCode.UNAUTHORIZED.getCode(), "临时业务令牌无效或已过期");
        }
        long remainTtl = TempTokenHelper.getTempTokenTimeout(token);
        return Result.success(TempTokenVO.builder()
                .token(token.trim())
                .ttlSeconds(remainTtl)
                .payload(payload)
                .build());
    }

    /**
     * 主动销毁临时业务令牌（实现阅后即焚一次性消费闭环）
     *
     * @param token 待销毁的临时业务令牌凭据
     * @return 成功响应
     */
    @DeleteMapping("/temp-token/revoke")
    public Result<Void> revokeTempToken(
            @NotBlank(message = "临时令牌不能为空") @RequestParam("token") String token) {
        LoginHelper.checkLogin();
        TempTokenHelper.deleteTempToken(token);
        return Result.success();
    }
}
