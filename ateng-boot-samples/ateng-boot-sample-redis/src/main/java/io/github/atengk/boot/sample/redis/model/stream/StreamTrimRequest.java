package io.github.atengk.boot.sample.redis.model.stream;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Stream 流定长裁剪请求参数 (XTRIM)
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreamTrimRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Stream 键名
     */
    @NotBlank(message = "Stream 键名不能为空")
    private String stream;

    /**
     * 裁剪保留最大条数
     */
    @NotNull(message = "保留最大条数不能为空")
    @Min(value = 0, message = "保留最大条数不能小于 0")
    private Long maxLen;
}
