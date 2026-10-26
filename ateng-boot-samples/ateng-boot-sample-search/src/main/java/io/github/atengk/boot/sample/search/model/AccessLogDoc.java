package io.github.atengk.boot.sample.search.model;

import io.github.atengk.boot.search.annotation.SearchId;
import io.github.atengk.boot.search.annotation.SearchIndex;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 动态时序日志文档实体（通过 SpEL 表达式按天分表）
 *
 * @author Ateng
 * @since 2026-10-26
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@SearchIndex(name = "access_log_#{T(java.time.LocalDate).now().format('yyyyMMdd')}")
public class AccessLogDoc {

    @SearchId
    private String id;

    private String path;

    private String clientIp;

    private LocalDateTime timestamp;
}
