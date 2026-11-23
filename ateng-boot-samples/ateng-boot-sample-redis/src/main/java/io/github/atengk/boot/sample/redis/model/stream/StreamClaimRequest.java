package io.github.atengk.boot.sample.redis.model.stream;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 孤儿消息认领转移请求参数 (XCLAIM)
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreamClaimRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Stream 键名
     */
    @NotBlank(message = "Stream 键名不能为空")
    private String stream;

    /**
     * 消费组名称
     */
    @NotBlank(message = "消费组名称不能为空")
    private String group;

    /**
     * 新接管消费者名称
     */
    @NotBlank(message = "接管消费者名称不能为空")
    private String newConsumer;

    /**
     * 最小闲置超时秒数
     */
    @NotNull(message = "最小闲置超时秒数不能为空")
    @Min(value = 0, message = "闲置秒数不能小于 0")
    private Long minIdleSeconds;

    /**
     * 待认领的消息 ID 列表
     */
    @NotEmpty(message = "待认领消息 ID 列表不能为空")
    private List<String> recordIds;
}
