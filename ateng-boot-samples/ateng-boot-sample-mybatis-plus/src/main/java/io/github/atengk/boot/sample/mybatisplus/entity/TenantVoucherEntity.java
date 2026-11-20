package io.github.atengk.boot.sample.mybatisplus.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.atengk.boot.mybatisplus.entity.BaseGenericLogicEntity;
import io.github.atengk.boot.mybatisplus.entity.TenantAware;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 多租户泛型凭证实体（String UUID 主键 + String 占位软删除 + Long 行级多租户隔离）
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
@TableName("tenant_voucher")
public class TenantVoucherEntity extends BaseGenericLogicEntity<String> implements TenantAware<Long> {

    private static final long serialVersionUID = 1L;

    /**
     * 32 位字符串 UUID 主键（由实体自定 @TableId 策略）
     */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /**
     * 凭证业务编号
     */
    private String voucherNo;

    /**
     * 凭证标题
     */
    private String title;

    /**
     * 数值型租户标识（行级多租户隔离）
     */
    private Long tenantId;
}
