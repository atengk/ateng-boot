package io.github.atengk.boot.sample.mybatisplus.model;

import io.github.atengk.boot.sample.mybatisplus.entity.ProductSkuEntity;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品 SKU 档案视图对象（包含回收站软删除占位标识展示）
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
public class ProductSkuVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键雪花 ID
     */
    private Long id;

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
     * 商品售价
     */
    private BigDecimal price;

    /**
     * 逻辑删除标记（0 表示正常未删除，非 0 表示已删除且值为自身雪花 ID 占位）
     */
    private Long deleted;

    /**
     * 乐观锁版本号
     */
    private Integer version;

    /**
     * 创建人
     */
    private String createBy;

    /**
     * 修改人
     */
    private String updateBy;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 修改时间
     */
    private LocalDateTime updateTime;

    /**
     * 从持久层商品 SKU 实体转换为视图对象
     *
     * @param entity 商品 SKU 实体
     * @return 商品 SKU 视图对象
     */
    public static ProductSkuVO fromEntity(ProductSkuEntity entity) {
        if (entity == null) {
            return null;
        }
        ProductSkuVO vo = new ProductSkuVO();
        vo.setId(entity.getId());
        vo.setSkuCode(entity.getSkuCode());
        vo.setSkuName(entity.getSkuName());
        vo.setCategory(entity.getCategory());
        vo.setPrice(entity.getPrice());
        vo.setDeleted(entity.getDeleted());
        vo.setVersion(entity.getVersion());
        vo.setCreateBy(entity.getCreateBy());
        vo.setUpdateBy(entity.getUpdateBy());
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateTime(entity.getUpdateTime());
        return vo;
    }
}
