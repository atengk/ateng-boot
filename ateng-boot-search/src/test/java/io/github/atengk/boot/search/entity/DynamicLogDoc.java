package io.github.atengk.boot.search.entity;

import io.github.atengk.boot.search.annotation.SearchId;
import io.github.atengk.boot.search.annotation.SearchIndex;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 具有 SpEL 动态时序索引后缀的日志测试实体
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@SearchIndex("log_#{T(java.time.LocalDate).now().format('yyyyMM')}")
public class DynamicLogDoc {

    @SearchId
    private String logId;

    private String level;

    private String message;

    private LocalDateTime timestamp;
}
