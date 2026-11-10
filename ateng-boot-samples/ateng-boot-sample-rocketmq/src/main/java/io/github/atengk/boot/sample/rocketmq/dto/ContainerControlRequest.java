package io.github.atengk.boot.sample.rocketmq.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 监听端点容器运维请求入参
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContainerControlRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 端点 ID 或主题名称
     */
    @NotBlank(message = "端点 ID 不能为空")
    private String endpointId;
}
