package io.github.atengk.boot.search.util;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import io.github.atengk.boot.search.enums.SearchResultCode;
import org.elasticsearch.client.ResponseException;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

/**
 * 搜索引擎底层异常状态码与类型分类识别工具
 * <p>
 * 统一解析 Elasticsearch 原生 SDK 抛出的异常因果链，识别 HTTP 状态码、版本冲突 (OCC 409)、
 * 资源未找到 (404)、瞬态限流/超时/网络故障以及认证/语法错误。
 *
 * @author Ateng
 * @since 2026-10-25
 */
public final class SearchExceptionClassifier {

    private SearchExceptionClassifier() {
    }

    /**
     * 从异常因果链中提取底层 HTTP 状态码
     *
     * @param t 异常对象
     * @return HTTP 状态码，若无法识别则返回 -1
     */
    public static int extractStatusCode(Throwable t) {
        if (t == null) {
            return -1;
        }
        if (t instanceof ElasticsearchException ee) {
            return ee.status();
        }
        if (t instanceof ResponseException re) {
            return re.getResponse().getStatusLine().getStatusCode();
        }
        return extractStatusCode(t.getCause());
    }

    /**
     * 判断异常因果链中是否存在文档版本冲突（OCC 乐观并发冲突，HTTP 409）
     *
     * @param t 异常对象
     * @return true 表示为版本冲突异常
     */
    public static boolean isVersionConflict(Throwable t) {
        if (t == null) {
            return false;
        }
        if (extractStatusCode(t) == 409) {
            return true;
        }
        String msg = t.getMessage();
        if (msg != null && (msg.contains("version_conflict_engine_exception")
                || msg.contains("version conflict")
                || msg.contains("409 Conflict"))) {
            return true;
        }
        return isVersionConflict(t.getCause());
    }

    /**
     * 判断异常是否为文档或索引不存在（HTTP 404）
     *
     * @param t 异常对象
     * @return true 表示为 404 Not Found
     */
    public static boolean isNotFound(Throwable t) {
        return extractStatusCode(t) == 404;
    }

    /**
     * 判断异常是否为客户端语法或参数错误（HTTP 400）
     *
     * @param t 异常对象
     * @return true 表示为 400 Bad Request
     */
    public static boolean isBadRequest(Throwable t) {
        return extractStatusCode(t) == 400;
    }

    /**
     * 判断异常是否为认证或授权失败（HTTP 401 / 403）
     *
     * @param t 异常对象
     * @return true 表示为认证/鉴权失败
     */
    public static boolean isAuthError(Throwable t) {
        int status = extractStatusCode(t);
        return status == 401 || status == 403;
    }

    /**
     * 判断异常是否为限流或背压拒绝（HTTP 429）
     *
     * @param t 异常对象
     * @return true 表示为 429 Too Many Requests
     */
    public static boolean isRateLimited(Throwable t) {
        return extractStatusCode(t) == 429;
    }

    /**
     * 判断异常是否为超时故障（SocketTimeoutException / TimeoutException / InterruptedIOException / 408 / 504）
     *
     * @param t 异常对象
     * @return true 表示为超时故障
     */
    public static boolean isTimeout(Throwable t) {
        int status = extractStatusCode(t);
        if (status == 408 || status == 504) {
            return true;
        }
        return containsExceptionType(t, SocketTimeoutException.class)
                || containsExceptionType(t, TimeoutException.class)
                || containsExceptionType(t, InterruptedIOException.class);
    }

    /**
     * 判断异常是否为网络连接故障（ConnectException / UnknownHostException / 502 / 503）
     *
     * @param t 异常对象
     * @return true 表示为网络连接故障
     */
    public static boolean isConnectError(Throwable t) {
        int status = extractStatusCode(t);
        if (status == 502 || status == 503) {
            return true;
        }
        return containsExceptionType(t, ConnectException.class)
                || containsExceptionType(t, UnknownHostException.class);
    }

    /**
     * 判断异常是否属于可重试的瞬态故障（网络抖动、超时、429 限流、502/503/504/408）
     *
     * @param t 异常对象
     * @return true 表示属于瞬态可重试故障
     */
    public static boolean isTransient(Throwable t) {
        if (t == null) {
            return false;
        }
        return isRateLimited(t) || isTimeout(t) || isConnectError(t);
    }

    /**
     * 将底层驱动异常因果链分类映射为标准 {@link SearchResultCode}
     *
     * @param t           底层异常
     * @param defaultCode 默认兜底错误码
     * @return 分类后的结果码
     */
    public static SearchResultCode classifyResultCode(Throwable t, SearchResultCode defaultCode) {
        SearchResultCode fallback = defaultCode != null ? defaultCode : SearchResultCode.SEARCH_OPERATION_ERROR;
        if (t == null) {
            return fallback;
        }
        if (isVersionConflict(t)) {
            return SearchResultCode.SEARCH_VERSION_CONFLICT;
        }
        if (isNotFound(t)) {
            return SearchResultCode.SEARCH_INDEX_NOT_FOUND;
        }
        if (isBadRequest(t)) {
            return SearchResultCode.SEARCH_BAD_REQUEST;
        }
        if (isAuthError(t)) {
            return SearchResultCode.SEARCH_AUTH_ERROR;
        }
        if (isRateLimited(t)) {
            return SearchResultCode.SEARCH_RATE_LIMITED;
        }
        if (isTimeout(t)) {
            return SearchResultCode.SEARCH_TIMEOUT_ERROR;
        }
        if (isConnectError(t)) {
            return SearchResultCode.SEARCH_ENGINE_CONNECT_ERROR;
        }
        return fallback;
    }

    private static boolean containsExceptionType(Throwable t, Class<? extends Throwable> targetType) {
        Throwable current = t;
        while (current != null) {
            if (targetType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
