package io.github.atengk.boot.sample.smoke.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.atengk.boot.mybatisplus.entity.BaseEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;

/**
 * 冒烟集成测试持久层实体
 * 仅用于持久层与 Service 内部流转，严禁跨越 Controller 边界向外暴露
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Getter
@Setter
@ToString(callSuper = true)
@NoArgsConstructor
@TableName("sys_smoke_record")
public class SmokeRecordEntity extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 记录标题
     */
    private String title;

    /**
     * 记录状态编码
     */
    private String status;

    /**
     * 金额
     */
    private BigDecimal amount;

    /**
     * 联系人手机号（内存中保持原始明文）
     */
    private String mobile;

    public SmokeRecordEntity(String title, String status, BigDecimal amount, String mobile) {
        this.title = title;
        this.status = status;
        this.amount = amount;
        this.mobile = mobile;
    }
}
