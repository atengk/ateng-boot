package io.github.atengk.boot.sample.flyway.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Flyway 健康快照响应视图对象
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MigrationHealthResponse {

    private String status;
    private Map<String, Object> details;
    private long databaseProbeCount;
}
