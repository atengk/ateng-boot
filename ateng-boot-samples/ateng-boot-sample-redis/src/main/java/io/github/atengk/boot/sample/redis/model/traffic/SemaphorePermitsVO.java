package io.github.atengk.boot.sample.redis.model.traffic;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 信号量配额视图对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemaphorePermitsVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String semaphoreName;
    private Integer currentPermits;
    private Boolean resetSuccess;
}
