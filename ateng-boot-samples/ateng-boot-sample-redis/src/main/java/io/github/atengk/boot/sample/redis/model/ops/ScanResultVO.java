package io.github.atengk.boot.sample.redis.model.ops;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * 游标扫描匹配结果视图对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScanResultVO {

    private String pattern;
    private Long count;
    private Set<String> matchedKeys;
    private Integer totalMatched;
}
