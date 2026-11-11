package io.github.atengk.boot.sample.admin.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import lombok.Data;

import java.time.Duration;

/**
 * 僵尸实例清理触发请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
public class ZombieCleanRequest {

    /**
     * 可选的临时超期判定阈值（如 PT0.01S 便于快速体验驱逐，未传则沿用系统配置）
     */
    private Duration zombieThresholdOverride;

    /**
     * 校验临时阈值不得为负数
     *
     * @return true 若未传或非负
     */
    @JsonIgnore
    @AssertTrue(message = "zombieThresholdOverride 不能为负数")
    public boolean isThresholdNonNegative() {
        return zombieThresholdOverride == null || !zombieThresholdOverride.isNegative();
    }
}
