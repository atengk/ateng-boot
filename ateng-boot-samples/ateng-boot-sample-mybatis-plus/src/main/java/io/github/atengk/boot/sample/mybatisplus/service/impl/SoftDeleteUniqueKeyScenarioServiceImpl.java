package io.github.atengk.boot.sample.mybatisplus.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.mybatisplus.service.impl.AtengServiceImpl;
import io.github.atengk.boot.mybatisplus.util.PageUtil;
import io.github.atengk.boot.sample.mybatisplus.entity.ProductSkuEntity;
import io.github.atengk.boot.sample.mybatisplus.mapper.ProductSkuMapper;
import io.github.atengk.boot.sample.mybatisplus.model.ProductSkuVO;
import io.github.atengk.boot.sample.mybatisplus.service.SoftDeleteUniqueKeyScenarioService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品 SKU 联合唯一键软删除与穿透恢复业务场景服务实现
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Service
public class SoftDeleteUniqueKeyScenarioServiceImpl
        extends AtengServiceImpl<ProductSkuMapper, ProductSkuEntity>
        implements SoftDeleteUniqueKeyScenarioService {

    @Override
    public List<ProductSkuVO> executeCycleSoftDelete(String skuCode) {
        BusinessAssert.hasText(skuCode, "商品 SKU 编码不能为空");

        // 1. 清理同编码活跃数据（若存在），确保重复调用幂等自洽
        List<ProductSkuEntity> existing = listWithDeleted(
                new LambdaQueryWrapper<ProductSkuEntity>().eq(ProductSkuEntity::getSkuCode, skuCode)
        );
        if (existing != null && !existing.isEmpty()) {
            for (ProductSkuEntity item : existing) {
                if (item.getDeleted() != null && item.getDeleted() == 0L) {
                    removeById(item.getId());
                }
            }
        }

        // 2. 首次上架商品 SKU 并立即执行软删除（底层自动触发 SET deleted = id）
        ProductSkuEntity firstGeneration = new ProductSkuEntity(
                skuCode, "无线降噪耳机初代版", "数码影音", new BigDecimal("1299.00")
        );
        save(firstGeneration);
        removeById(firstGeneration.getId());

        // 3. 使用完全相同的 skuCode 重新上架二代商品（uk_sku_code_deleted 零冲突）
        ProductSkuEntity secondGeneration = new ProductSkuEntity(
                skuCode, "无线降噪耳机二代旗舰版", "数码影音", new BigDecimal("1599.00")
        );
        save(secondGeneration);

        // 4. 穿透逻辑删除过滤，查询该 SKU 编码下的全部历史与当前活跃记录
        List<ProductSkuEntity> allGenerations = listWithDeleted(
                new LambdaQueryWrapper<ProductSkuEntity>()
                        .eq(ProductSkuEntity::getSkuCode, skuCode)
                        .orderByAsc(ProductSkuEntity::getId)
        );
        return allGenerations.stream()
                .map(ProductSkuVO::fromEntity)
                .toList();
    }

    @Override
    public PageResult<ProductSkuVO> pageRecycleBin(PageQuery pageQuery) {
        ensureSeedSkusIfEmpty();
        Page<ProductSkuEntity> page = PageUtil.toPage(pageQuery, ProductSkuEntity.class);
        Page<ProductSkuEntity> entityPage = pageWithDeleted(
                page,
                new LambdaQueryWrapper<ProductSkuEntity>().orderByAsc(ProductSkuEntity::getId)
        );
        return PageUtil.toPageResult(entityPage, ProductSkuVO::fromEntity);
    }

    @Override
    public ProductSkuVO restoreSkuById(Long id, String operator) {
        BusinessAssert.notNull(id, "待恢复的商品 SKU 主键 ID 不能为空");
        UserContextHolder.set(StringUtils.hasText(operator) ? operator : "admin-restorer");
        try {
            boolean restored = restoreById(id);
            BusinessAssert.isTrue(restored, "未找到可恢复的商品 SKU 记录: id=" + id);
            return ProductSkuVO.fromEntity(getById(id));
        } finally {
            UserContextHolder.clear();
        }
    }

    @Override
    public int restoreSkuByCode(String skuCode) {
        LambdaQueryWrapper<ProductSkuEntity> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(skuCode)) {
            wrapper.eq(ProductSkuEntity::getSkuCode, skuCode);
        }
        try {
            return restore(wrapper);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @Override
    public boolean restoreSkusByIds(List<Long> ids, String operator) {
        UserContextHolder.set(StringUtils.hasText(operator) ? operator : "admin-restorer");
        try {
            return restoreByIds(ids);
        } finally {
            UserContextHolder.clear();
        }
    }

    @Override
    public long countRecycleBin() {
        return countWithDeleted(null);
    }

    @Override
    public PageResult<ProductSkuVO> pageRecycleBinVO(PageQuery pageQuery) {
        ensureSeedSkusIfEmpty();
        return pageWithDeleted(
                pageQuery,
                new LambdaQueryWrapper<ProductSkuEntity>().orderByAsc(ProductSkuEntity::getId),
                ProductSkuVO.class
        );
    }

    @Override
    public boolean purgeSkuPhysically(Long id) {
        BusinessAssert.notNull(id, "待物理硬删除的商品 SKU 主键 ID 不能为空");
        return removePhysicallyById(id);
    }

    private void ensureSeedSkusIfEmpty() {
        List<ProductSkuEntity> all = listWithDeleted(null);
        if (all == null || all.isEmpty()) {
            ProductSkuEntity deletedSku = new ProductSkuEntity(
                    "SKU-SEED-001", "机械键盘标准版（已下架）", "外设办公", new BigDecimal("499.00")
            );
            save(deletedSku);
            removeById(deletedSku.getId());

            ProductSkuEntity activeSku = new ProductSkuEntity(
                    "SKU-SEED-002", "人体工学鼠标旗舰版", "外设办公", new BigDecimal("699.00")
            );
            save(activeSku);
        }
    }
}
