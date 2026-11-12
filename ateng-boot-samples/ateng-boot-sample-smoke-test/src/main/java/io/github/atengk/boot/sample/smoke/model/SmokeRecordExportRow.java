package io.github.atengk.boot.sample.smoke.model;

import io.github.atengk.boot.core.enums.SensitiveType;
import io.github.atengk.boot.excel.annotation.ExcelSensitive;
import io.github.atengk.boot.sample.smoke.entity.SmokeRecordEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.fesod.sheet.annotation.ExcelProperty;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 冒烟记录 Excel 导出行模型
 * 导出单元格写入阶段通过 @ExcelSensitive 自动动态脱敏，源数据在内存中保持原始明文
 *
 * @author Ateng
 * @since 2026-11-12
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SmokeRecordExportRow implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @ExcelProperty("记录标题")
    private String title;

    @ExcelProperty("状态")
    private String status;

    @ExcelProperty("金额")
    private BigDecimal amount;

    @ExcelProperty("联系人手机")
    @ExcelSensitive(SensitiveType.MOBILE_PHONE)
    private String mobile;

    @ExcelProperty("创建人")
    private String createBy;

    /**
     * 将持久层实体映射为 Excel 导出行模型
     *
     * @param entity 持久层实体
     * @return Excel 导出行模型，入参为 null 时返回 null
     */
    public static SmokeRecordExportRow fromEntity(SmokeRecordEntity entity) {
        if (entity == null) {
            return null;
        }
        return new SmokeRecordExportRow(
                entity.getTitle(),
                entity.getStatus(),
                entity.getAmount(),
                entity.getMobile(),
                entity.getCreateBy()
        );
    }
}
