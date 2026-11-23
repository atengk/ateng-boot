package io.github.atengk.boot.sample.mybatisplus.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.core.util.ValidatorUtil;
import io.github.atengk.boot.mybatisplus.service.impl.AtengServiceImpl;
import io.github.atengk.boot.sample.mybatisplus.entity.TenantVoucherEntity;
import io.github.atengk.boot.sample.mybatisplus.mapper.TenantVoucherMapper;
import io.github.atengk.boot.sample.mybatisplus.model.TenantVoucherCreateDTO;
import io.github.atengk.boot.sample.mybatisplus.model.TenantVoucherVO;
import io.github.atengk.boot.sample.mybatisplus.service.TenantVoucherService;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 多租户泛型凭证业务服务实现
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Service
public class TenantVoucherServiceImpl extends AtengServiceImpl<TenantVoucherMapper, TenantVoucherEntity>
        implements TenantVoucherService {

    @Override
    public TenantVoucherVO createVoucher(TenantVoucherCreateDTO dto, Long tenantId, String operator) {
        BusinessAssert.notNull(dto, "创建凭证请求参数不能为空");
        ValidatorUtil.validate(dto);
        BusinessAssert.notNull(tenantId, "租户标识 tenantId 不能为空");

        TenantContextHolder.setTenantId(tenantId);
        UserContextHolder.set(operator);
        try {
            TenantVoucherEntity entity = new TenantVoucherEntity();
            entity.setVoucherNo(dto.getVoucherNo());
            entity.setTitle(dto.getTitle());
            save(entity);
            return TenantVoucherVO.fromEntity(getById(entity.getId()));
        } finally {
            UserContextHolder.clear();
            TenantContextHolder.clear();
        }
    }

    @Override
    public boolean softDeleteVoucher(String id, Long tenantId) {
        BusinessAssert.hasText(id, "凭证主键 id 不能为空");
        BusinessAssert.notNull(tenantId, "租户标识 tenantId 不能为空");

        TenantContextHolder.setTenantId(tenantId);
        try {
            return removeById(id);
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Override
    public TenantVoucherVO restoreVoucher(String id, Long tenantId, String operator) {
        BusinessAssert.hasText(id, "凭证主键 id 不能为空");
        BusinessAssert.notNull(tenantId, "租户标识 tenantId 不能为空");

        TenantContextHolder.setTenantId(tenantId);
        UserContextHolder.set(operator);
        try {
            boolean restored = restoreById(id);
            BusinessAssert.isTrue(restored, "未找到可恢复的凭证记录: id=" + id);
            return TenantVoucherVO.fromEntity(getById(id));
        } finally {
            UserContextHolder.clear();
            TenantContextHolder.clear();
        }
    }

    @Override
    public List<TenantVoucherVO> listActiveVouchers(Long tenantId) {
        BusinessAssert.notNull(tenantId, "租户标识 tenantId 不能为空");

        TenantContextHolder.setTenantId(tenantId);
        try {
            List<TenantVoucherEntity> list = list(new LambdaQueryWrapper<TenantVoucherEntity>()
                    .orderByAsc(TenantVoucherEntity::getCreateTime));
            if (list.isEmpty()) {
                return Collections.emptyList();
            }
            return list.stream().map(TenantVoucherVO::fromEntity).toList();
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Override
    public List<TenantVoucherVO> listRecycleBinVouchers(Long tenantId) {
        BusinessAssert.notNull(tenantId, "租户标识 tenantId 不能为空");

        TenantContextHolder.setTenantId(tenantId);
        try {
            List<TenantVoucherEntity> list = listWithDeleted(new LambdaQueryWrapper<TenantVoucherEntity>()
                    .orderByAsc(TenantVoucherEntity::getCreateTime));
            if (list.isEmpty()) {
                return Collections.emptyList();
            }
            return list.stream().map(TenantVoucherVO::fromEntity).toList();
        } finally {
            TenantContextHolder.clear();
        }
    }
}
