package io.github.atengk.boot.web.handler;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import io.github.atengk.boot.web.trace.TraceFilter;
import org.springframework.validation.BindingResult;

/**
 * 全局统一异常安全拦截处理器
 * 捕获控制器抛出的各类已知业务异常与未知系统异常，将其统一转译为标准 Result 结构，对未知根异常强制生产脱敏并关联 MDC 链路追踪标识
 *
 * @author Ateng
 * @since 2026-10-15
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 拦截受控业务中断异常 BusinessException
     * 当异常携带底层驱动 cause 根因时，记录完整调用栈与 traceId 闭环；无 cause 时以 INFO 记录轻量业务提示
     *
     * @param e 业务异常
     * @return 业务错误响应结果
     */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        if (e.getCause() != null) {
            String traceId = MDC.get(TraceFilter.MDC_TRACE_ID_KEY);
            log.error("[TraceId: {}] 业务因果异常中断: code={}, msg={}, cause: ", traceId, e.getCode(), e.getMsg(), e);
        } else {
            log.info("业务逻辑中断: code={}, msg={}", e.getCode(), e.getMsg());
        }
        return Result.fail(e.getCode(), e.getMsg());
    }

    /**
     * 拦截 Controller 实体参数校验异常 MethodArgumentNotValidException
     *
     * @param e 参数校验异常
     * @return 400 校验提示响应结果
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        String errorMsg = extractBindingErrorMessage(e.getBindingResult());
        log.warn("参数校验未通过: {}", errorMsg);
        return Result.fail(CommonResultCode.BAD_REQUEST.getCode(), errorMsg);
    }

    /**
     * 拦截表单绑定校验异常 BindException
     *
     * @param e 参数绑定异常
     * @return 400 绑定提示响应结果
     */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException e) {
        String errorMsg = extractBindingErrorMessage(e.getBindingResult());
        log.warn("表单参数绑定未通过: {}", errorMsg);
        return Result.fail(CommonResultCode.BAD_REQUEST.getCode(), errorMsg);
    }

    /**
     * 拦截单个方法参数约束异常 ConstraintViolationException
     *
     * @param e 约束违背异常
     * @return 400 约束违背提示响应结果
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolationException(ConstraintViolationException e) {
        String errorMsg = e.getConstraintViolations().stream()
                .findFirst()
                .map(ConstraintViolation::getMessage)
                .orElse(CommonResultCode.BAD_REQUEST.getMsg());
        log.warn("方法级约束校验未通过: {}", errorMsg);
        return Result.fail(CommonResultCode.BAD_REQUEST.getCode(), errorMsg);
    }

    /**
     * 拦截请求体 JSON 反序列化解析异常 HttpMessageNotReadableException
     *
     * @param e HTTP 消息不可读异常
     * @return 400 反序列化提示响应结果
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        log.warn("请求体反序列化失败: {}", e.getMessage());
        return Result.fail(CommonResultCode.BAD_REQUEST.getCode(), "请求参数反序列化失败，请检查 JSON 格式");
    }

    /**
     * 拦截客户端入参缺失或类型转换异常
     *
     * @param e 客户端请求入参异常
     * @return 400 校验提示响应结果
     */
    @ExceptionHandler({
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.bind.MissingRequestHeaderException.class
    })
    public Result<Void> handleClientParamMismatchException(Exception e) {
        log.warn("客户端请求参数非法或缺失: {}", e.getMessage());
        return Result.fail(CommonResultCode.BAD_REQUEST.getCode(), "请求参数非法或缺失");
    }

    /**
     * 拦截不支持的 HTTP 请求方法异常 HttpRequestMethodNotSupportedException
     *
     * @param e 请求方法不支持异常
     * @return 405 方法不支持提示响应结果
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleHttpRequestMethodNotSupportedException(HttpRequestMethodNotSupportedException e) {
        log.warn("HTTP 请求方法不支持: {}", e.getMethod());
        return Result.fail(CommonResultCode.METHOD_NOT_ALLOWED.getCode(), "请求方法不支持: " + e.getMethod());
    }

    /**
     * 拦截静态资源或路由不存在异常 NoResourceFoundException
     *
     * @param e 资源未找到异常
     * @return 404 响应结果
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNoResourceFoundException(NoResourceFoundException e) {
        log.debug("请求资源未找到: {}", e.getResourcePath());
        return Result.fail(CommonResultCode.NOT_FOUND);
    }

    /**
     * 兜底拦截所有未捕获的根异常 Throwable / Exception
     * 记录完整错误堆栈与当前 MDC traceId，向前端返回生产脱敏提示
     *
     * @param e 未知根异常
     * @return 500 生产脱敏响应结果
     */
    @ExceptionHandler(Throwable.class)
    public Result<Void> handleThrowable(Throwable e) {
        String traceId = MDC.get(TraceFilter.MDC_TRACE_ID_KEY);
        log.error("[TraceId: {}] 系统发生未处理异常: ", traceId, e);
        return Result.fail(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), CommonResultCode.INTERNAL_SERVER_ERROR.getMsg());
    }

    private String extractBindingErrorMessage(BindingResult bindingResult) {
        if (bindingResult == null) {
            return CommonResultCode.BAD_REQUEST.getMsg();
        }
        return bindingResult.getAllErrors().stream()
                .findFirst()
                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                .orElse(CommonResultCode.BAD_REQUEST.getMsg());
    }
}
