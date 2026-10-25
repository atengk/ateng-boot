package io.github.atengk.boot.search.util;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import org.elasticsearch.client.ResponseException;

/**
 * 搜索引擎底层异常状态码与类型分类识别工具
 * <p>
 * 统一解析 Elasticsearch 原生 SDK 抛出的异常因果链，识别 HTTP 状态码、版本冲突 (OCC 409) 及资源未找到 (404)。
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
}
