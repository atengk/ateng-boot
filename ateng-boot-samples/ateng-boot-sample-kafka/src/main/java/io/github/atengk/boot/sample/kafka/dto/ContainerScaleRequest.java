package io.github.atengk.boot.sample.kafka.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 监听容器运行期并发度动态扩缩请求 DTO
 *
 * @author Ateng
 * @since 2026-11-15
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContainerScaleRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 容器注册 ID 或订阅主题名称
     */
    @NotBlank(message = "容器ID或主题名称不能为空")
    private String containerIdOrTopic;

    /**
     * 目标并发线程数（必须大于等于 1）
     */
    @NotNull(message = "并发度不能为空")
    @Min(value = 1, message = "并发度必须大于等于 1")
    private Integer concurrency;
}
