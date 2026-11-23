package io.github.atengk.boot.sample.search.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 审计日志按条件批量更新客户端 IP 请求 DTO (参数化 UpdateByQuery)
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccessLogMarkIpRequest {

    /**
     * 过滤命中的接口路径
     */
    @NotBlank(message = "接口路径不能为空")
    private String path;

    /**
     * 待更新的客户端 IP 标记值
     */
    @NotBlank(message = "客户端 IP 不能为空")
    private String clientIp;
}
