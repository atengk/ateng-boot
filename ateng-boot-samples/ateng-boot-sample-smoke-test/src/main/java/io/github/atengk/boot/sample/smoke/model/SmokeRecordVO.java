package io.github.atengk.boot.sample.smoke.model;

import io.github.atengk.boot.core.annotation.FieldSensitive;
import io.github.atengk.boot.core.enums.SensitiveType;
import io.github.atengk.boot.sample.smoke.entity.SmokeRecordEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 冒烟记录响应视图对象
 *
 * @author Ateng
 * @since 2026-11-12
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SmokeRecordVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID
     */
    private Long id;

    /**
     * 记录标题
     */
    private String title;

    /**
     * 状态编码
     */
    private String status;

    /**
     * 金额
     */
    private BigDecimal amount;

    /**
     * 联系人手机号（视图层脱敏展示）
     */
    @FieldSensitive(SensitiveType.MOBILE_PHONE)
    private String mobile;

    /**
     * 创建人（审计自动填充）
     */
    private String createBy;

    /**
     * 创建时间（审计自动填充）
     */
    private LocalDateTime createTime;

    /**
     * 将持久层实体映射转换为视图响应 VO
     *
     * @param entity 持久层实体（允许为 null）
     * @return 视图响应 VO，入参为 null 时返回 null
     */
    public static SmokeRecordVO fromEntity(SmokeRecordEntity entity) {
        if (entity == null) {
            return null;
        }
        return new SmokeRecordVO(
                entity.getId(),
                entity.getTitle(),
                entity.getStatus(),
                entity.getAmount(),
                entity.getMobile(),
                entity.getCreateBy(),
                entity.getCreateTime()
        );
    }
}
