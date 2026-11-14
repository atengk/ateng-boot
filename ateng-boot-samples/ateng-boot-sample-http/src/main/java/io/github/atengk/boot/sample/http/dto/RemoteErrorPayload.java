package io.github.atengk.boot.sample.http.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 远端服务返回的结构化错误响应载荷
 * 用于演示 HttpRemoteException#getResponseBodyAs 类型安全反序列化
 *
 * @author Ateng
 * @since 2026-11-14
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RemoteErrorPayload implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 下游系统业务错误码
     */
    private String errorCode;

    /**
     * 下游系统错误描述信息
     */
    private String errorMessage;

    /**
     * 下游流水追踪号
     */
    private String requestId;
}
