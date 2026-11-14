package io.github.atengk.boot.sample.http.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Set;

/**
 * HTTP HEAD / OPTIONS 端点元数据探测视图对象
 *
 * @author Ateng
 * @since 2026-11-14
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EndpointMetadataVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 探测目标资源标识
     */
    private Long resourceId;

    /**
     * HEAD 响应 Content-Type 媒体类型
     */
    private String contentType;

    /**
     * HEAD 响应 Content-Length 字节长度
     */
    private long contentLength;

    /**
     * 自定义版本元数据响应头 (X-Resource-Version)
     */
    private String resourceVersion;

    /**
     * OPTIONS 探测返回的能力声明响应头 (X-Endpoint-Capability)
     */
    private String endpointCapability;

    /**
     * OPTIONS 解析的 Allow 允许 HTTP 动词集合
     */
    private Set<String> allowedMethods;

    /**
     * OPTIONS 返回的响应头数量
     */
    private int optionsHeaderCount;
}
