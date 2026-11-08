package io.github.atengk.boot.sample.redis.model.lock;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 稀缺名额公平锁排队请求对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FairQuotaRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "活动 ID 不能为空")
    private String activityId;

    @NotBlank(message = "用户 ID 不能为空")
    private String userId;
}
