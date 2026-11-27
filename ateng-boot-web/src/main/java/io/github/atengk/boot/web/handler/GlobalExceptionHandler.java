package io.github.atengk.boot.web.handler;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.web.trace.TraceFilter;
import io.github.atengk.boot.web.util.ServletUtil;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.util.DisconnectedClientHelper;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 全局统一异常安全拦截处理器
 * 捕获控制器抛出的各类已知业务异常、Web 容器与传输层精细化异常以及未知系统异常，将其统一转译为标准 Result 结构；
 * 支持挂载 {@link GlobalExceptionCustomizer} SPI 扩展点实现链式探测与领域异常定制拦截，对未知根异常强制生产脱敏并关联 MDC 链路追踪标识
 *
 * @author Ateng
 * @since 2026-10-15
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 请求属性标记：标识本次请求已由全局异常处理器产生语义化错误响应
     */
    public static final String ERROR_RESPONSE_ATTRIBUTE = GlobalExceptionHandler.class.getName() + ".ERROR_RESPONSE";

    private static final int MAX_CAUSE_CHAIN_DEPTH = 32;
    private static final int MAX_ROOT_CAUSE_LOG_LENGTH = 256;
    private static final String MSG_MULTIPART_INVALID = "文件上传请求格式不合法或内容解析失败";
    private static final String MSG_MULTIPART_CLIENT_ABORT = "文件上传传输中断，请检查网络后重试";
    private static final String MSG_MULTIPART_FILE_COUNT_EXCEEDED = "上传文件或表单分片数量超出限制";

    private static final Set<String> CLIENT_DISCONNECT_TYPE_NAMES = Set.of(
            "AbortedException",
            "ClientAbortException",
            "EOFException",
            "EofException",
            "AsyncRequestNotUsableException"
    );

    private static final List<String> CLIENT_DISCONNECT_MESSAGE_KEYWORDS = List.of(
            "broken pipe",
            "connection reset",
            "connection was aborted",
            "connection abort",
            "closed by the remote host",
            "an established connection was aborted",
            "unexpected eof",
            "client abort",
            "远程主机强迫关闭了一个现有的连接"
    );

    private static final Set<String> CLIENT_MALFORMED_TYPE_NAMES = Set.of(
            "MalformedStreamException",
            "InvalidContentTypeException",
            "IllegalBoundaryException",
            "InvalidFileNameException",
            "ParseException"
    );

    private static final List<String> SERVER_MULTIPART_CONFIG_KEYWORDS = List.of(
            "no multipart config",
            "no multi-part configuration"
    );

    private static final List<String> SERVER_STORAGE_IO_KEYWORDS = List.of(
            "temporary upload location",
            "create the upload location",
            "no space left on device",
            "not enough space on the disk",
            "disk quota exceeded"
    );

    private final List<GlobalExceptionCustomizer> customizers;

    public GlobalExceptionHandler() {
        this(Collections.emptyList());
    }

    public GlobalExceptionHandler(List<GlobalExceptionCustomizer> customizers) {
        if (customizers != null) {
            this.customizers = customizers.stream()
                    .sorted(AnnotationAwareOrderComparator.INSTANCE)
                    .toList();
        } else {
            this.customizers = Collections.emptyList();
        }
    }

    private Result<?> tryCustomize(Throwable throwable) {
        jakarta.servlet.http.HttpServletRequest request = ServletUtil.getRequest();
        if (request != null) {
            request.setAttribute(ERROR_RESPONSE_ATTRIBUTE, Boolean.TRUE);
        }
        if (customizers != null && !customizers.isEmpty()) {
            for (GlobalExceptionCustomizer customizer : customizers) {
                try {
                    Result<?> result = customizer.customize(throwable);
                    if (result != null) {
                        return result;
                    }
                } catch (Exception ex) {
                    log.warn("GlobalExceptionCustomizer [{}] 执行异常", customizer.getClass().getName(), ex);
                }
            }
        }
        return null;
    }

    /**
     * 拦截受控业务中断异常 BusinessException
     * 当异常携带底层驱动 cause 根因时，记录完整调用栈与 traceId 闭环；无 cause 时以 INFO 记录轻量业务提示；
     * 若当前线程 MDC 无 traceId 且异常携带跨进程 traceId，则兜底回填至 Result.traceId
     *
     * @param e 业务异常
     * @return 业务错误响应结果
     */
    @ExceptionHandler(BusinessException.class)
    public Result<?> handleBusinessException(BusinessException e) {
        String mdcTraceId = MDC.get(TraceFilter.MDC_TRACE_ID_KEY);
        String effectiveTraceId = org.springframework.util.StringUtils.hasText(mdcTraceId) ? mdcTraceId : e.getTraceId();
        Result<?> customized = tryCustomize(e);
        if (customized != null) {
            return applyFallbackTraceId(customized, mdcTraceId, e.getTraceId());
        }
        if (e.getCause() != null) {
            log.error("[TraceId: {}] 业务因果异常中断: code={}, msg={}, cause: ", effectiveTraceId, e.getCode(), e.getMsg(), e);
        } else {
            log.info("业务逻辑中断: code={}, msg={}", e.getCode(), e.getMsg());
        }
        return applyFallbackTraceId(Result.fail(e.getCode(), e.getMsg()), mdcTraceId, e.getTraceId());
    }

    private Result<?> applyFallbackTraceId(Result<?> result, String mdcTraceId, String exceptionTraceId) {
        if (!org.springframework.util.StringUtils.hasText(mdcTraceId)
                && !org.springframework.util.StringUtils.hasText(result.getTraceId())
                && org.springframework.util.StringUtils.hasText(exceptionTraceId)) {
            result.traceId(exceptionTraceId);
        }
        return result;
    }

    /**
     * 拦截 Controller 实体参数校验异常 MethodArgumentNotValidException
     *
     * @param e 参数校验异常
     * @return 400 校验提示响应结果
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<?> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        Result<?> customized = tryCustomize(e);
        if (customized != null) {
            return customized;
        }
        String errorMsg = extractBindingErrorMessage(e.getBindingResult());
        log.warn("参数校验未通过: {}", errorMsg);
        return Result.fail(CommonResultCode.BAD_REQUEST.getCode(), errorMsg);
    }

    /**
     * 拦截方法参数校验异常 HandlerMethodValidationException (Spring 6.1+)
     *
     * @param e 方法参数校验异常
     * @return 400 校验提示响应结果
     */
    @ExceptionHandler(org.springframework.web.method.annotation.HandlerMethodValidationException.class)
    public Result<?> handleHandlerMethodValidationException(org.springframework.web.method.annotation.HandlerMethodValidationException e) {
        Result<?> customized = tryCustomize(e);
        if (customized != null) {
            return customized;
        }
        String errorMsg = e.getAllErrors().stream()
                .findFirst()
                .map(org.springframework.context.MessageSourceResolvable::getDefaultMessage)
                .orElse(CommonResultCode.BAD_REQUEST.getMsg());
        log.warn("方法参数校验未通过: {}", errorMsg);
        return Result.fail(CommonResultCode.BAD_REQUEST.getCode(), errorMsg);
    }

    /**
     * 拦截表单绑定校验异常 BindException
     *
     * @param e 参数绑定异常
     * @return 400 绑定提示响应结果
     */
    @ExceptionHandler(BindException.class)
    public Result<?> handleBindException(BindException e) {
        Result<?> customized = tryCustomize(e);
        if (customized != null) {
            return customized;
        }
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
    public Result<?> handleConstraintViolationException(ConstraintViolationException e) {
        Result<?> customized = tryCustomize(e);
        if (customized != null) {
            return customized;
        }
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
    public Result<?> handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        Result<?> customized = tryCustomize(e);
        if (customized != null) {
            return customized;
        }
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
    public Result<?> handleClientParamMismatchException(Exception e) {
        Result<?> customized = tryCustomize(e);
        if (customized != null) {
            return customized;
        }
        log.warn("客户端请求参数非法或缺失: {}", e.getMessage());
        return Result.fail(CommonResultCode.BAD_REQUEST.getCode(), "请求参数非法或缺失");
    }

    /**
     * 拦截缺少必要文件或表单分片异常 MissingServletRequestPartException
     *
     * @param e 缺少分片异常
     * @return 400 校验提示响应结果
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public Result<?> handleMissingServletRequestPartException(MissingServletRequestPartException e) {
        Result<?> customized = tryCustomize(e);
        if (customized != null) {
            return customized;
        }
        log.warn("缺少必要文件或表单分片: partName={}", e.getRequestPartName());
        return Result.fail(CommonResultCode.BAD_REQUEST.getCode(), "缺少必要的文件或表单分片: " + e.getRequestPartName());
    }

    /**
     * 拦截不支持的 HTTP 请求方法异常 HttpRequestMethodNotSupportedException
     *
     * @param e 请求方法不支持异常
     * @return 405 方法不支持提示响应结果
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<?> handleHttpRequestMethodNotSupportedException(HttpRequestMethodNotSupportedException e) {
        Result<?> customized = tryCustomize(e);
        if (customized != null) {
            return customized;
        }
        log.warn("HTTP 请求方法不支持: {}", e.getMethod());
        return Result.fail(CommonResultCode.METHOD_NOT_ALLOWED.getCode(), "请求方法不支持: " + e.getMethod());
    }

    /**
     * 拦截不接受的媒体类型异常 HttpMediaTypeNotAcceptableException
     * 当客户端 Accept 请求头拒绝 application/json 时，直接向 HttpServletResponse 输出标准 JSON 契约以防次生异常
     *
     * @param e        媒体类型不接受异常
     * @param response HTTP 响应对象
     * @return 406 响应结果（若已直接写入响应则返回 null 标记已处理）
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public Result<?> handleHttpMediaTypeNotAcceptableException(HttpMediaTypeNotAcceptableException e, HttpServletResponse response) {
        Result<?> customized = tryCustomize(e);
        Result<?> result = (customized != null) ? customized
                : Result.fail(CommonResultCode.NOT_ACCEPTABLE.getCode(), CommonResultCode.NOT_ACCEPTABLE.getMsg());
        log.warn("不接受的媒体类型: {}", e.getMessage());
        if (response != null && !response.isCommitted()) {
            try {
                String traceId = MDC.get(TraceFilter.MDC_TRACE_ID_KEY);
                if (org.springframework.util.StringUtils.hasText(traceId) && !response.containsHeader(TraceFilter.DEFAULT_HEADER)) {
                    response.setHeader(TraceFilter.DEFAULT_HEADER, traceId);
                }
                response.setStatus(HttpServletResponse.SC_OK);
                ServletUtil.writeJson(response, JsonUtil.toJsonString(result));
                return null;
            } catch (IOException ex) {
                log.error("写入 406 JSON 响应失败", ex);
            }
        }
        return result;
    }

    /**
     * 拦截上传文件大小超出限制异常 MaxUploadSizeExceededException
     *
     * @param e 上传大小超限异常
     * @return 413 响应结果
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<?> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e) {
        Result<?> customized = tryCustomize(e);
        if (customized != null) {
            return customized;
        }
        log.warn("上传文件大小超出限制: maxUploadSize={}", e.getMaxUploadSize());
        return Result.fail(CommonResultCode.PAYLOAD_TOO_LARGE.getCode(), CommonResultCode.PAYLOAD_TOO_LARGE.getMsg());
    }

    /**
     * 拦截非体积超限的 Multipart 请求解析与上传异常 MultipartException
     * 保持子类 {@link MaxUploadSizeExceededException}（413）优先匹配不变，并按原因链执行三轨分级：
     * 1. 服务端容器配置缺失或磁盘/临时目录故障：保留 ERROR 堆栈与 500 生产脱敏响应，防真实故障被掩盖；
     * 2. 客户端传输中途断连：降噪记录精简 WARN 并返回 400 传输中断提示；
     * 3. 分片/文件数量超限或普通畸形/非 multipart 请求：记录精简 WARN 并返回 400 友好提示
     *
     * @param e Multipart 异常
     * @return 400 或服务端故障脱敏 500 响应结果
     */
    @ExceptionHandler(MultipartException.class)
    public Result<?> handleMultipartException(MultipartException e) {
        Result<?> customized = tryCustomize(e);
        if (customized != null) {
            return customized;
        }

        CauseChain causeChain = collectCauseChain(e);
        MultipartFailureKind failureKind = classifyMultipartFailure(e, causeChain);
        return switch (failureKind) {
            case SERVER_FAILURE -> {
                String traceId = MDC.get(TraceFilter.MDC_TRACE_ID_KEY);
                log.error("[TraceId: {}] 文件上传服务端异常: ", traceId, e);
                yield Result.fail(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), CommonResultCode.INTERNAL_SERVER_ERROR.getMsg());
            }
            case CLIENT_DISCONNECTED -> {
                log.warn("文件上传连接中断: rootCause={}", resolveRootCauseSummary(causeChain.causes()));
                yield Result.fail(CommonResultCode.BAD_REQUEST.getCode(), MSG_MULTIPART_CLIENT_ABORT);
            }
            case FILE_COUNT_EXCEEDED -> {
                log.warn("上传文件或表单分片数量超出限制: rootCause={}", resolveRootCauseSummary(causeChain.causes()));
                yield Result.fail(CommonResultCode.BAD_REQUEST.getCode(), MSG_MULTIPART_FILE_COUNT_EXCEEDED);
            }
            case INVALID_FORMAT -> {
                log.warn("文件上传请求解析失败: rootCause={}", resolveRootCauseSummary(causeChain.causes()));
                yield Result.fail(CommonResultCode.BAD_REQUEST.getCode(), MSG_MULTIPART_INVALID);
            }
        };
    }

    /**
     * 拦截不支持的媒体类型异常 HttpMediaTypeNotSupportedException
     *
     * @param e 媒体类型不支持异常
     * @return 415 响应结果
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public Result<?> handleHttpMediaTypeNotSupportedException(HttpMediaTypeNotSupportedException e) {
        Result<?> customized = tryCustomize(e);
        if (customized != null) {
            return customized;
        }
        log.warn("不支持的媒体类型: contentType={}", e.getContentType());
        return Result.fail(CommonResultCode.UNSUPPORTED_MEDIA_TYPE.getCode(), CommonResultCode.UNSUPPORTED_MEDIA_TYPE.getMsg());
    }

    /**
     * 拦截异步请求处理超时异常 AsyncRequestTimeoutException
     *
     * @param e 异步超时异常
     * @return 503 响应结果
     */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public Result<?> handleAsyncRequestTimeoutException(AsyncRequestTimeoutException e) {
        Result<?> customized = tryCustomize(e);
        if (customized != null) {
            return customized;
        }
        log.warn("异步请求处理超时");
        return Result.fail(CommonResultCode.SERVICE_UNAVAILABLE.getCode(), "异步请求处理超时");
    }

    /**
     * 拦截静态资源或路由不存在异常 NoResourceFoundException
     *
     * @param e 资源未找到异常
     * @return 404 响应结果
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<?> handleNoResourceFoundException(NoResourceFoundException e) {
        Result<?> customized = tryCustomize(e);
        if (customized != null) {
            return customized;
        }
        log.debug("请求资源未找到: {}", e.getResourcePath());
        return Result.fail(CommonResultCode.NOT_FOUND);
    }

    /**
     * 兜底拦截所有未捕获的根异常 Throwable / Exception
     * 优先探测 GlobalExceptionCustomizer SPI，若无定制器处理则向前端返回生产脱敏提示并记录完整错误堆栈与 MDC traceId
     *
     * @param e 未知根异常
     * @return 500 生产脱敏响应结果
     */
    @ExceptionHandler(Throwable.class)
    public Result<?> handleThrowable(Throwable e) {
        Result<?> customized = tryCustomize(e);
        if (customized != null) {
            return customized;
        }
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

    private CauseChain collectCauseChain(Throwable root) {
        List<Throwable> causes = new ArrayList<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = root;
        boolean acyclic = true;
        int depth = 0;
        while (current != null) {
            if (!visited.add(current) || depth >= MAX_CAUSE_CHAIN_DEPTH) {
                acyclic = false;
                break;
            }
            causes.add(current);
            current = current.getCause();
            depth++;
        }
        return new CauseChain(causes, acyclic);
    }

    private MultipartFailureKind classifyMultipartFailure(MultipartException exception, CauseChain causeChain) {
        List<Throwable> causes = causeChain.causes();
        if (isServerMultipartFailure(causes)) {
            return MultipartFailureKind.SERVER_FAILURE;
        }
        if (isClientDisconnectedMultipartFailure(exception, causeChain)) {
            return MultipartFailureKind.CLIENT_DISCONNECTED;
        }
        if (isFileCountLimitExceeded(causes)) {
            return MultipartFailureKind.FILE_COUNT_EXCEEDED;
        }
        return MultipartFailureKind.INVALID_FORMAT;
    }

    private boolean isServerMultipartFailure(List<Throwable> causes) {
        for (Throwable current : causes) {
            if (current instanceof FileSystemException) {
                return true;
            }
            if (isClientMalformedType(current)) {
                continue;
            }
            String lowerMsg = toLowerMessage(current);
            if (lowerMsg.isEmpty()) {
                continue;
            }
            if ((current instanceof IllegalStateException || current instanceof ServletException)
                    && containsAnyKeyword(lowerMsg, SERVER_MULTIPART_CONFIG_KEYWORDS)) {
                return true;
            }
            if (current instanceof IOException
                    && !isFileUploadWrapperType(current)
                    && containsAnyKeyword(lowerMsg, SERVER_STORAGE_IO_KEYWORDS)) {
                return true;
            }
        }
        return false;
    }

    private boolean isClientDisconnectedMultipartFailure(MultipartException exception, CauseChain causeChain) {
        List<Throwable> causes = causeChain.causes();
        // 畸形流截断（如 MalformedStreamException: Stream ended unexpectedly）归类为格式非法，不误判为客户端断连
        for (Throwable current : causes) {
            if (isClientMalformedType(current)) {
                return false;
            }
        }
        if (causeChain.acyclic() && DisconnectedClientHelper.isClientDisconnectedException(exception)) {
            return true;
        }
        for (Throwable current : causes) {
            if (CLIENT_DISCONNECT_TYPE_NAMES.contains(current.getClass().getSimpleName())) {
                return true;
            }
            if ((current instanceof IOException || current instanceof ServletException)
                    && containsAnyKeyword(toLowerMessage(current), CLIENT_DISCONNECT_MESSAGE_KEYWORDS)) {
                return true;
            }
        }
        return false;
    }

    private boolean isFileCountLimitExceeded(List<Throwable> causes) {
        for (Throwable current : causes) {
            if ("FileCountLimitExceededException".equals(current.getClass().getSimpleName())) {
                return true;
            }
            if (isClientMalformedType(current)) {
                continue;
            }
            String lowerMsg = toLowerMessage(current);
            if (lowerMsg.contains("file count") || lowerMsg.contains("part count") || lowerMsg.contains("too many parts")) {
                return true;
            }
        }
        return false;
    }

    private boolean isClientMalformedType(Throwable throwable) {
        return CLIENT_MALFORMED_TYPE_NAMES.contains(throwable.getClass().getSimpleName());
    }

    private boolean isFileUploadWrapperType(Throwable throwable) {
        String simpleName = throwable.getClass().getSimpleName();
        return "FileUploadException".equals(simpleName)
                || "IOFileUploadException".equals(simpleName)
                || "FileCountLimitExceededException".equals(simpleName);
    }

    private String toLowerMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message != null ? message.toLowerCase(Locale.ROOT) : "";
    }

    private boolean containsAnyKeyword(String lowerMessage, List<String> keywords) {
        if (lowerMessage.isEmpty()) {
            return false;
        }
        for (String keyword : keywords) {
            if (lowerMessage.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String resolveRootCauseSummary(List<Throwable> causes) {
        if (causes.isEmpty()) {
            return MultipartException.class.getSimpleName();
        }
        for (int i = causes.size() - 1; i >= 0; i--) {
            Throwable current = causes.get(i);
            String msg = current.getMessage();
            if (msg != null && !msg.isBlank()) {
                String sanitized = sanitizeLogMessage(msg);
                return current.getClass().getSimpleName() + ": " + sanitized;
            }
        }
        return causes.get(causes.size() - 1).getClass().getSimpleName();
    }

    private String sanitizeLogMessage(String message) {
        StringBuilder sb = new StringBuilder(Math.min(message.length(), MAX_ROOT_CAUSE_LOG_LENGTH));
        int len = message.length();
        for (int i = 0; i < len && sb.length() < MAX_ROOT_CAUSE_LOG_LENGTH; i++) {
            char c = message.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        String cleaned = sb.toString().trim();
        if (len > MAX_ROOT_CAUSE_LOG_LENGTH) {
            return cleaned + " ... [TRUNCATED]";
        }
        return cleaned;
    }

    private enum MultipartFailureKind {
        SERVER_FAILURE,
        CLIENT_DISCONNECTED,
        FILE_COUNT_EXCEEDED,
        INVALID_FORMAT
    }

    private record CauseChain(List<Throwable> causes, boolean acyclic) {
    }
}
