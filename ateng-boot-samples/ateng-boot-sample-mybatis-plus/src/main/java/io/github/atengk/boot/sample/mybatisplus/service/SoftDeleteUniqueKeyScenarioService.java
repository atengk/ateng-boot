package io.github.atengk.boot.sample.mybatisplus.service;

import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.mybatisplus.service.AtengBaseService;
import io.github.atengk.boot.sample.mybatisplus.entity.ProductSkuEntity;
import io.github.atengk.boot.sample.mybatisplus.model.ProductSkuVO;

import java.util.List;

/**
 * 商品 SKU 联合唯一键软删除与穿透恢复业务场景服务契约
 *
 * @author Ateng
 * @since 2026-10-27
 */
public interface SoftDeleteUniqueKeyScenarioService extends AtengBaseService<ProductSkuEntity> {

    /**
     * 演示商品 SKU 联合唯一索引 (sku_code, deleted) 在多次“新增 -> 软删除 -> 同编码重建”闭环中零物理冲突
     *
     * @param skuCode 商品 SKU 唯一编码
     * @return 包含历史已软删除记录与最新活跃记录的 SKU 视图列表
     */
    List<ProductSkuVO> executeCycleSoftDelete(String skuCode);

    /**
     * 分页查询商品 SKU 回收站全量视图（穿透逻辑删除，若表为空自动初始化演示种子）
     *
     * @param pageQuery 标准分页查询契约
     * @return 商品 SKU 分页视图结果
     */
    PageResult<ProductSkuVO> pageRecycleBin(PageQuery pageQuery);

    /**
     * 按主键 ID 恢复已逻辑删除的商品 SKU 记录并返回视图对象
     *
     * @param id       商品 SKU 主键 ID
     * @param operator 执行恢复的操作人标识
     * @return 恢复后的商品 SKU 视图对象
     */
    ProductSkuVO restoreSkuById(Long id, String operator);

    /**
     * 按商品 SKU 编码条件批量恢复逻辑删除记录（空条件触发防全表误恢复卫语句拦截）
     *
     * @param skuCode 商品 SKU 编码
     * @return 成功恢复的记录行数
     */
    int restoreSkuByCode(String skuCode);
}
