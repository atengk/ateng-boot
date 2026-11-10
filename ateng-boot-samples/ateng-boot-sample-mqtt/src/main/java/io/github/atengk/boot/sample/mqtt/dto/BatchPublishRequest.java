package io.github.atengk.boot.sample.mqtt.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 批量并发下发请求 DTO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchPublishRequest implements Serializable {

    @Valid
    @NotEmpty(message = "批量下发记录列表 records 不能为空")
    private List<BatchPublishItemRequest> records;
}
