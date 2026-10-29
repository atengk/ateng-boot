package io.github.atengk.boot.http.properties;

import lombok.Data;
import org.springframework.util.unit.DataSize;

import java.util.ArrayList;
import java.util.List;

/**
 * 出站 HTTP 请求与响应结构化日志配置属性类
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Data
public class HttpLoggingProperties {

    /**
     * 是否启用出站 HTTP 日志拦截器（默认：true）
     */
    private boolean enabled = true;

    /**
     * 日志输出级别（默认：BASIC 基础动词、URL、状态码与耗时）
     */
    private HttpLoggingLevel level = HttpLoggingLevel.BASIC;

    /**
     * 出站请求头与响应头敏感脱敏黑名单列表（不区分大小写匹配，匹配成功自动打星号掩码）
     */
    private List<String> maskHeaders = new ArrayList<>(List.of(
            "Authorization",
            "Cookie",
            "Set-Cookie",
            "X-Api-Key",
            "token",
            "access-token"
    ));

    /**
     * 出站 URL 查询参数敏感脱敏黑名单列表（不区分大小写匹配，匹配成功自动打星号掩码）
     */
    private List<String> maskParams = new ArrayList<>(List.of(
            "secret",
            "password",
            "token",
            "apiKey",
            "api_key",
            "key",
            "access_token"
    ));

    /**
     * FULL 级别下请求体与响应体最大打印字符长度（超过上限安全截断并追加省略标识，默认：1024 字符）
     */
    private int maxBodyLength = 1024;

    /**
     * 单次响应体最大安全缓冲容量上限（超过该容量放弃响应体缓冲与打印，防止大流爆内存，默认：64KB）
     */
    private DataSize maxBufferCapacity = DataSize.ofKilobytes(64);
}
