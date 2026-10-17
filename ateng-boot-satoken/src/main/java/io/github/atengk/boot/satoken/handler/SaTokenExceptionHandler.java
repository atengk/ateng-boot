package io.github.atengk.boot.satoken.handler;

import cn.dev33.satoken.exception.DisableServiceException;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.dev33.satoken.exception.NotSafeException;
import cn.dev33.satoken.exception.SaTokenException;
import cn.dev33.satoken.exception.SameTokenInvalidException;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.model.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Sa-Token 鉴权异常全局映射处理器
 * 统一拦截登录、权限、角色、二级认证等鉴权框架异常，保持 HTTP 200 与统一 Result 显式契约响应
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestControllerAdvice")
public class SaTokenExceptionHandler {

    /**
     * 拦截未登录异常 (NotLoginException)
     *
     * @param e 异常实例
     * @return 401 细粒度未登录响应
     */
    @ExceptionHandler(NotLoginException.class)
    public Result<Void> handleNotLoginException(NotLoginException e) {
        String msg = switch (e.getType()) {
            case NotLoginException.NOT_TOKEN -> "未提供访问凭据 (Token)";
            case NotLoginException.INVALID_TOKEN -> "访问凭据 (Token) 无效";
            case NotLoginException.TOKEN_TIMEOUT -> "访问凭据 (Token) 已过期，请重新登录";
            case NotLoginException.BE_REPLACED -> "当前账号已在其他设备登录，您已被顶下线";
            case NotLoginException.KICK_OUT -> "当前账号已被踢下线";
            case NotLoginException.TOKEN_FREEZE -> "当前账号已被冻结";
            case NotLoginException.NO_PREFIX -> "访问凭据缺少指定前缀";
            default -> "会话未登录或登录状态已失效";
        };
        log.warn("鉴权拦截 - 会话未登录: type={}, message={}", e.getType(), e.getMessage());
        return Result.fail(CommonResultCode.UNAUTHORIZED.getCode(), msg);
    }

    /**
     * 拦截角色不足异常 (NotRoleException)
     *
     * @param e 异常实例
     * @return 403 权限不足响应
     */
    @ExceptionHandler(NotRoleException.class)
    public Result<Void> handleNotRoleException(NotRoleException e) {
        log.warn("鉴权拦截 - 缺少角色: role={}", e.getRole());
        return Result.fail(CommonResultCode.FORBIDDEN.getCode(), "无权访问：缺少所需角色 [" + e.getRole() + "]");
    }

    /**
     * 拦截权限码不足异常 (NotPermissionException)
     *
     * @param e 异常实例
     * @return 403 权限不足响应
     */
    @ExceptionHandler(NotPermissionException.class)
    public Result<Void> handleNotPermissionException(NotPermissionException e) {
        log.warn("鉴权拦截 - 缺少权限: permission={}", e.getPermission());
        return Result.fail(CommonResultCode.FORBIDDEN.getCode(), "无权访问：缺少所需权限码 [" + e.getPermission() + "]");
    }

    /**
     * 拦截二级安全认证未通过异常 (NotSafeException)
     *
     * @param e 异常实例
     * @return 403 安全拦截响应
     */
    @ExceptionHandler(NotSafeException.class)
    public Result<Void> handleNotSafeException(NotSafeException e) {
        log.warn("鉴权拦截 - 二级安全认证未通过: service={}", e.getService());
        return Result.fail(CommonResultCode.FORBIDDEN.getCode(), "无权访问：当前操作需要二级安全认证");
    }

    /**
     * 拦截账号或服务封禁异常 (DisableServiceException)
     *
     * @param e 异常实例
     * @return 403 服务封禁响应
     */
    @ExceptionHandler(DisableServiceException.class)
    public Result<Void> handleDisableServiceException(DisableServiceException e) {
        log.warn("鉴权拦截 - 服务已被封禁: service={}, disableTime={}", e.getService(), e.getDisableTime());
        return Result.fail(CommonResultCode.FORBIDDEN.getCode(), "当前账号已被封禁至 " + e.getDisableTime());
    }

    /**
     * 拦截微服务内部 Same-Token 无效异常 (SameTokenInvalidException)
     *
     * @param e 异常实例
     * @return 401 凭据无效响应
     */
    @ExceptionHandler(SameTokenInvalidException.class)
    public Result<Void> handleSameTokenInvalidException(SameTokenInvalidException e) {
        log.warn("鉴权拦截 - 微服务内部 Same-Token 无效: message={}", e.getMessage());
        return Result.fail(CommonResultCode.UNAUTHORIZED.getCode(), "微服务内部鉴权凭据无效或已失效");
    }

    /**
     * 拦截其它通用 Sa-Token 异常 (SaTokenException)
     *
     * @param e 异常实例
     * @return 400 业务错误响应
     */
    @ExceptionHandler(SaTokenException.class)
    public Result<Void> handleSaTokenException(SaTokenException e) {
        log.warn("鉴权拦截 - Sa-Token 框架异常: message={}", e.getMessage());
        return Result.fail(CommonResultCode.BAD_REQUEST.getCode(), e.getMessage());
    }
}
