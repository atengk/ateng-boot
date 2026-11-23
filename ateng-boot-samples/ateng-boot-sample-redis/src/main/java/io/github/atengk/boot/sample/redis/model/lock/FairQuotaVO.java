package io.github.atengk.boot.sample.redis.model.lock;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 稀缺名额排队视图对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FairQuotaVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String activityId;
    private String userId;
    private Integer queueNumber;
    private Boolean acquired;
}
