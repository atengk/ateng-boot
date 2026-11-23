package io.github.atengk.boot.sample.mybatisplus.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 原生批量操作商品 SKU 入参传输对象
 * 零持久层实体泄露，用于批量插入、双方言批量 Upsert 与主键防死锁升序更新
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BatchSkuDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 商品 SKU 主键 ID（新增时可选，更新/Upsert 时必传）
     */
    private Long id;

    /**
     * 商品 SKU 唯一编码
     */
    @NotBlank(message = "SKU编码不能为空")
    private String skuCode;

    /**
     * 商品 SKU 名称
     */
    @NotBlank(message = "SKU名称不能为空")
    private String skuName;

    /**
     * 所属类目
     */
    private String category;

    /**
     * 商品售价
     */
    @NotNull(message = "商品售价不能为空")
    private BigDecimal price;

    public BatchSkuDTO(String skuCode, String skuName, BigDecimal price) {
        this.skuCode = skuCode;
        this.skuName = skuName;
        this.price = price;
        this.category = "数码数显";
    }
}
