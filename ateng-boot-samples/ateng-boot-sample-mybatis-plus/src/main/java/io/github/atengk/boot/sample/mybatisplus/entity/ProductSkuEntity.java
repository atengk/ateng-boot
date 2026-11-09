package io.github.atengk.boot.sample.mybatisplus.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.atengk.boot.mybatisplus.annotation.AuditChange;
import io.github.atengk.boot.mybatisplus.annotation.ConflictKeys;
import io.github.atengk.boot.mybatisplus.entity.BaseDeleteIdEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;

/**
 * 商品 SKU 档案实体对象
 * 继承 BaseDeleteIdEntity，通过 (sku_code, deleted) 联合唯一索引消除多次逻辑删除物理冲突，
 * 并支持通过 LogicDeleteContextHolder 与 AtengLogicDeleteInnerInterceptor 执行回收站穿透查询与误删恢复。
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@TableName("product_sku")
@ConflictKeys({"sku_code", "deleted"})
@AuditChange(description = "商品SKU档案审计")
public class ProductSkuEntity extends BaseDeleteIdEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 商品 SKU 唯一编码
     */
    private String skuCode;

    /**
     * 商品 SKU 名称
     */
    private String skuName;

    /**
     * 商品所属类目
     */
    private String category;

    /**
     * 商品标准售价
     */
    private BigDecimal price;

    public ProductSkuEntity(String skuCode, String skuName) {
        this.skuCode = skuCode;
        this.skuName = skuName;
        this.category = "默认数码类目";
        this.price = new BigDecimal("299.00");
    }
}
