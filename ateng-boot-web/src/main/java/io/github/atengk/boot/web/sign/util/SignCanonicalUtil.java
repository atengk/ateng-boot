package io.github.atengk.boot.web.sign.util;

import io.github.atengk.boot.core.util.DigestUtil;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * API 加签验签标准化待签串工具类
 * 严格对齐金融级统一拼装协议，消解排版空格与参数乱序引起的签名哈希漂移
 *
 * @author Ateng
 * @since 2026-10-16
 */
public final class SignCanonicalUtil {

    private SignCanonicalUtil() {
    }

    /**
     * 构建入站请求标准化待签串
     * 规范格式：Method\nURI\nTimestamp\nNonce\nSortedQueryString\nBodySha256
     *
     * @param method       HTTP 请求方法（如 POST, GET）
     * @param uri          请求 URI（如 /api/orders）
     * @param timestamp    客户端时间戳
     * @param nonce        随机字符串 Nonce
     * @param parameterMap 请求 Query 参数映射
     * @param bodySha256   请求体 SHA-256 小写十六进制摘要
     * @return 标准化待签名字符串
     */
    public static String buildRequestCanonicalString(String method,
                                                     String uri,
                                                     String timestamp,
                                                     String nonce,
                                                     Map<String, String[]> parameterMap,
                                                     String bodySha256) {
        String cleanMethod = method != null ? method.toUpperCase() : "GET";
        String cleanUri = StringUtils.hasText(uri) ? uri : "/";
        String cleanTimestamp = timestamp != null ? timestamp : "";
        String cleanNonce = nonce != null ? nonce : "";
        String sortedQuery = buildSortedQueryString(parameterMap);
        String cleanBodySha256 = StringUtils.hasText(bodySha256) ? bodySha256.toLowerCase() : DigestUtil.sha256Hex("");

        return String.join("\n", cleanMethod, cleanUri, cleanTimestamp, cleanNonce, sortedQuery, cleanBodySha256);
    }

    /**
     * 构建出站响应标准化待签串
     * 规范格式：Timestamp\nNonce\nResponseBodySha256
     *
     * @param timestamp          出站响应时间戳
     * @param nonce              出站响应随机 Nonce
     * @param responseBodySha256 响应体 JSON SHA-256 小写十六进制摘要
     * @return 标准化待签名字符串
     */
    public static String buildResponseCanonicalString(String timestamp,
                                                      String nonce,
                                                      String responseBodySha256) {
        String cleanTimestamp = timestamp != null ? timestamp : "";
        String cleanNonce = nonce != null ? nonce : "";
        String cleanBodySha256 = StringUtils.hasText(responseBodySha256) ? responseBodySha256.toLowerCase() : DigestUtil.sha256Hex("");

        return String.join("\n", cleanTimestamp, cleanNonce, cleanBodySha256);
    }

    /**
     * 将请求参数映射按 ASCII 字典序排序组装为标准化 Query 串 (k1=v1&k2=v2)
     *
     * @param parameterMap 请求参数映射
     * @return 排序后的 Query 字符串；无参数时返回空字符串
     */
    public static String buildSortedQueryString(Map<String, String[]> parameterMap) {
        if (parameterMap == null || parameterMap.isEmpty()) {
            return "";
        }

        List<String> sortedKeys = new ArrayList<>(parameterMap.keySet());
        Collections.sort(sortedKeys);

        List<String> pairs = new ArrayList<>();
        for (String key : sortedKeys) {
            String[] values = parameterMap.get(key);
            if (values == null || values.length == 0) {
                pairs.add(key + "=");
            } else {
                String[] sortedValues = Arrays.copyOf(values, values.length);
                Arrays.sort(sortedValues);
                for (String val : sortedValues) {
                    pairs.add(key + "=" + (val != null ? val : ""));
                }
            }
        }

        return String.join("&", pairs);
    }
}
