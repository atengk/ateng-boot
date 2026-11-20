package io.github.atengk.boot.sample.mybatisplus.model;

import io.github.atengk.boot.sample.mybatisplus.entity.TenantVoucherEntity;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 多租户泛型凭证视图对象
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Data
public class TenantVoucherVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;
    private String voucherNo;
    private String title;
    private Long tenantId;
    private String deleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String createBy;
    private String updateBy;

    /**
     * 将持久层凭证实体转换为对外展示视图对象
     *
     * @param entity 凭证实体
     * @return 凭证视图对象，或 {@code null}
     */
    public static TenantVoucherVO fromEntity(TenantVoucherEntity entity) {
        if (entity == null) {
            return null;
        }
        TenantVoucherVO vo = new TenantVoucherVO();
        vo.setId(entity.getId());
        vo.setVoucherNo(entity.getVoucherNo());
        vo.setTitle(entity.getTitle());
        vo.setTenantId(entity.getTenantId());
        vo.setDeleted(entity.getDeleted());
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateTime(entity.getUpdateTime());
        vo.setCreateBy(entity.getCreateBy());
        vo.setUpdateBy(entity.getUpdateBy());
        return vo;
    }
}
