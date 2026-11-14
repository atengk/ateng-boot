package io.github.atengk.boot.sample.http.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * getEntity 完整 HTTP 响应元信息与载荷提取视图对象
 *
 * @author Ateng
 * @since 2026-11-14
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResponseEntityVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 远端 HTTP 响应状态码
     */
    private int httpStatus;

    /**
     * 从响应头提取的自定义版本头 (X-Resource-Version)
     */
    private String resourceVersion;

    /**
     * 响应 Content-Type 头
     */
    private String contentType;

    /**
     * 反序列化后的强类型业务响应体
     */
    private UserResponse body;
}
