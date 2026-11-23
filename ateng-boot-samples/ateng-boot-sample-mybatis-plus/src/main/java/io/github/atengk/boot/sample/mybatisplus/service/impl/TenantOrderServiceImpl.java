package io.github.atengk.boot.sample.mybatisplus.service.impl;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.mybatisplus.service.impl.AtengServiceImpl;
import io.github.atengk.boot.sample.mybatisplus.entity.TenantOrderEntity;
import io.github.atengk.boot.sample.mybatisplus.mapper.TenantOrderMapper;
import io.github.atengk.boot.sample.mybatisplus.model.TenantIsolationCompareVO;
import io.github.atengk.boot.sample.mybatisplus.model.TenantOrderVO;
import io.github.atengk.boot.sample.mybatisplus.service.TenantOrderService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * 租户订单业务服务实现
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Service
public class TenantOrderServiceImpl extends AtengServiceImpl<TenantOrderMapper, TenantOrderEntity> implements TenantOrderService {

    @Override
    public List<TenantOrderVO> listTenantOrders(String tenantId) {
        BusinessAssert.hasText(tenantId, "租户标识 tenantId 不能为空");
        ensureSeedTenantOrdersIfEmpty();

        TenantContextHolder.setTenantId(tenantId);
        try {
            List<TenantOrderEntity> orders = list();
            if (orders == null || orders.isEmpty()) {
                return Collections.emptyList();
            }
            return orders.stream().map(TenantOrderVO::fromEntity).toList();
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Override
    public TenantIsolationCompareVO compareTenantIsolationAndBypass(String currentTenantId) {
        BusinessAssert.hasText(currentTenantId, "当前租户标识 currentTenantId 不能为空");
        ensureSeedTenantOrdersIfEmpty();

        TenantContextHolder.setTenantId(currentTenantId);
        try {
            // 1. 当前租户行级隔离查询（AST 自动追加 WHERE tenant_id = ?）
            List<TenantOrderEntity> scopedOrders = list();

            // 2. 通过 Mapper @IgnoreTenant 声明式豁免查询全平台订单
            List<TenantOrderEntity> byAnnotation = baseMapper.selectAllOrdersIgnoreTenant();

            // 3. 通过 TenantContextHolder.runWithoutTenant 编程式闭包豁免查询全平台订单
            List<TenantOrderEntity> byClosure = TenantContextHolder.runWithoutTenant(() -> list());

            TenantIsolationCompareVO compareVO = new TenantIsolationCompareVO();
            compareVO.setCurrentTenantId(currentTenantId);
            compareVO.setTenantScopedCount(scopedOrders != null ? scopedOrders.size() : 0);
            compareVO.setIgnoreTenantAnnotationCount(byAnnotation != null ? byAnnotation.size() : 0);
            compareVO.setRunWithoutTenantClosureCount(byClosure != null ? byClosure.size() : 0);
            compareVO.setTenantScopedOrders(scopedOrders != null
                    ? scopedOrders.stream().map(TenantOrderVO::fromEntity).toList()
                    : Collections.emptyList());
            compareVO.setGlobalOrders(byAnnotation != null
                    ? byAnnotation.stream().map(TenantOrderVO::fromEntity).toList()
                    : Collections.emptyList());
            return compareVO;
        } finally {
            TenantContextHolder.clear();
        }
    }

    private void ensureSeedTenantOrdersIfEmpty() {
        long total = TenantContextHolder.runWithoutTenant(() -> count());
        if (total == 0L) {
            TenantContextHolder.setTenantId("tenant_A");
            try {
                save(new TenantOrderEntity("ORD_A_001", "租户A企业云主机采购单", "SKU_CLOUD_01", new BigDecimal("1999.00"), "PAID"));
                save(new TenantOrderEntity("ORD_A_002", "租户A专线带宽续费单", "SKU_NET_02", new BigDecimal("899.00"), "CREATED"));
            } finally {
                TenantContextHolder.clear();
            }

            TenantContextHolder.setTenantId("tenant_B");
            try {
                save(new TenantOrderEntity("ORD_B_001", "租户B智能客服SaaS订阅单", "SKU_AI_01", new BigDecimal("3999.00"), "PAID"));
            } finally {
                TenantContextHolder.clear();
            }
        }
    }
}
