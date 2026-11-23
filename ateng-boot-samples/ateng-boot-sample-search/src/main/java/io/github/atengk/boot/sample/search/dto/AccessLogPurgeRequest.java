package io.github.atengk.boot.sample.search.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 历史日志物理清理请求 DTO (DeleteByQuery)
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccessLogPurgeRequest {

    @NotNull(message = "清理截止时间不能为空")
    private LocalDateTime cutoffTime;
}
