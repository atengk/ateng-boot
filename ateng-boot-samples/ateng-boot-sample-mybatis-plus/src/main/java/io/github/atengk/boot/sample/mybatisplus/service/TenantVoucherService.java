package io.github.atengk.boot.sample.mybatisplus.service;

import io.github.atengk.boot.mybatisplus.service.AtengBaseService;
import io.github.atengk.boot.sample.mybatisplus.entity.TenantVoucherEntity;
import io.github.atengk.boot.sample.mybatisplus.model.TenantVoucherCreateDTO;
import io.github.atengk.boot.sample.mybatisplus.model.TenantVoucherVO;

import java.util.List;

/**
 * 多租户泛型凭证业务服务接口
 *
 * @author Ateng
 * @since 2026-11-20
 */
public interface TenantVoucherService extends AtengBaseService<TenantVoucherEntity> {

    /**
     * 在指定租户与操作人上下文下创建凭证
     *
     * @param dto      创建请求参数
     * @param tenantId 数值租户标识
     * @param operator 操作人标识
     * @return 创建后的凭证视图对象
     */
    TenantVoucherVO createVoucher(TenantVoucherCreateDTO dto, Long tenantId, String operator);

    /**
     * 按字符串主键逻辑删除凭证（占位写入 id 列值）
     *
     * @param id       凭证主键
     * @param tenantId 租户标识
     * @return 是否删除成功
     */
    boolean softDeleteVoucher(String id, Long tenantId);

    /**
     * 按字符串主键从回收站恢复凭证（还原 deleted = '0'）
     *
     * @param id       凭证主键
     * @param tenantId 租户标识
     * @param operator 操作人标识
     * @return 恢复后的凭证视图对象
     */
    TenantVoucherVO restoreVoucher(String id, Long tenantId, String operator);

    /**
     * 查询当前租户下的活跃凭证列表
     *
     * @param tenantId 租户标识
     * @return 凭证视图列表
     */
    List<TenantVoucherVO> listActiveVouchers(Long tenantId);

    /**
     * 穿透逻辑删除查询指定租户下包含回收站记录的凭证列表
     *
     * @param tenantId 租户标识
     * @return 包含软删占位的凭证视图列表
     */
    List<TenantVoucherVO> listRecycleBinVouchers(Long tenantId);
}
