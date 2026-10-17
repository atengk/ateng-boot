package io.github.atengk.boot.sample.smoke.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.atengk.boot.mybatisplus.entity.BaseEntity;
import org.apache.fesod.sheet.annotation.ExcelProperty;

import java.math.BigDecimal;

/**
 * 冒烟集成测试实体
 * 兼具 MyBatis-Plus 持久化与 Apache Fesod Excel 导出标注能力
 *
 * @author Ateng
 * @since 2026-10-17
 */
@TableName("sys_smoke_record")
public class SmokeRecordEntity extends BaseEntity {

    @ExcelProperty("记录标题")
    private String title;

    @ExcelProperty("金额")
    private BigDecimal amount;

    public SmokeRecordEntity() {
    }

    public SmokeRecordEntity(String title, BigDecimal amount) {
        this.title = title;
        this.amount = amount;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
}
