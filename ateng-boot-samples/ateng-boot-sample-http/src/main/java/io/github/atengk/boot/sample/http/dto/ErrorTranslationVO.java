package io.github.atengk.boot.sample.http.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * HTTP 错误因果转译与错误体反序列化验证视图对象
 *
 * @author Ateng
 * @since 2026-11-14
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorTranslationVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 映射后的语义化 HttpResultCode 整数码
     */
    private int mappedResultCode;

    /**
     * 映射后的语义化错误提示文案
     */
    private String mappedResultMessage;

    /**
     * 远端原始 HTTP 状态码
     */
    private int httpStatusCode;

    /**
     * 出站请求的远端 URL
     */
    private String requestUrl;

    /**
     * 反序列化提取的远端结构化错误码
     */
    private String remoteErrorCode;

    /**
     * 反序列化提取的远端结构化错误描述
     */
    private String remoteErrorMessage;

    /**
     * 反序列化提取的远端请求流水号
     */
    private String remoteRequestId;

    /**
     * 结构化错误体是否成功反序列化
     */
    private boolean responseBodyTranslated;
}
