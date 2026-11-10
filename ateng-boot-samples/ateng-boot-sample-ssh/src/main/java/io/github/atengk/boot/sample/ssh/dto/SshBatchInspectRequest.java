package io.github.atengk.boot.sample.ssh.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 集群多节点高级批量巡检请求 DTO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SshBatchInspectRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 待巡检的节点名称列表
     */
    @NotEmpty(message = "巡检节点集合不能为空")
    private List<@NotBlank(message = "节点名称不能为空") String> serverNames;

    /**
     * 巡检待下发的统一命令
     */
    @NotBlank(message = "巡检命令不能为空")
    private String command;

    /**
     * 并发度上限（默认 10）
     */
    @Min(value = 1, message = "并发度最小为 1")
    @Max(value = 50, message = "并发度最大为 50")
    @Builder.Default
    private Integer concurrency = 10;

    /**
     * 遇到节点失败是否立即熔断
     */
    @Builder.Default
    private Boolean failFast = false;

    /**
     * 单节点超时秒数（可选）
     */
    @Min(value = 1, message = "超时时间最小为 1 秒")
    private Long timeoutSeconds;
}
