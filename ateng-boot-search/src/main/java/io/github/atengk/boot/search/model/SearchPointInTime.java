package io.github.atengk.boot.search.model;

import org.springframework.util.StringUtils;

import java.io.Serializable;
import java.time.Duration;
import java.util.Objects;

/**
 * 搜索引擎一致性快照点 (Point-In-Time, PIT) 不可变句柄模型
 * <p>
 * 用于配合 {@code Search-After} 在固定索引视图快照上执行稳定深分页，
 * 避免并发写入导致翻页重复或漏数；使用完毕后须显式关闭或通过
 * {@link io.github.atengk.boot.search.template.SearchTemplate#executeWithPointInTime} 确定性释放。
 *
 * @param pitId     服务端颁发的 PIT 快照唯一标识
 * @param index     目标索引名或别名
 * @param keepAlive 快照存活保活时长
 * @author Ateng
 * @since 2026-11-20
 */
public record SearchPointInTime(String pitId, String index, Duration keepAlive) implements Serializable {

    public SearchPointInTime {
        if (!StringUtils.hasText(pitId)) {
            throw new IllegalArgumentException("pitId must not be blank");
        }
        if (!StringUtils.hasText(index)) {
            throw new IllegalArgumentException("index must not be blank");
        }
        Objects.requireNonNull(keepAlive, "keepAlive must not be null");
        if (keepAlive.isZero() || keepAlive.isNegative()) {
            throw new IllegalArgumentException("keepAlive must be positive: " + keepAlive);
        }
    }
}
