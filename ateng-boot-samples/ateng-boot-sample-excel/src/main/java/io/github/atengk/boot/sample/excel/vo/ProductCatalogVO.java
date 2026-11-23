package io.github.atengk.boot.sample.excel.vo;

import io.github.atengk.boot.excel.annotation.ExcelImage;
import org.apache.fesod.sheet.annotation.ExcelProperty;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 商品图文画册 Excel 视图对象
 * <p>
 * 演示 @ExcelImage 多媒体图片自适应内嵌导出与行高自动撑起（75pt）。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-08
 */
public class ProductCatalogVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 商品编号
     */
    @ExcelProperty(value = "商品编号", index = 0)
    private Long id;

    /**
     * 商品名称
     */
    @ExcelProperty(value = "商品名称", index = 1)
    private String name;

    /**
     * 商品品类
     */
    @ExcelProperty(value = "所属品类", index = 2)
    private String category;

    /**
     * 销售单价
     */
    @ExcelProperty(value = "销售单价", index = 3)
    private BigDecimal price;

    /**
     * 商品效果图（自适应行高 75pt）
     */
    @ExcelImage(rowHeight = 75, connectTimeout = 5000, readTimeout = 5000)
    @ExcelProperty(value = "商品效果图", index = 4)
    private byte[] image;

    /**
     * 库存状态
     */
    @ExcelProperty(value = "库存状态", index = 5)
    private String stockStatus;

    public ProductCatalogVO() {
    }

    public ProductCatalogVO(Long id, String name, String category, BigDecimal price, byte[] image, String stockStatus) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.price = price;
        this.image = image;
        this.stockStatus = stockStatus;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public byte[] getImage() {
        return image;
    }

    public void setImage(byte[] image) {
        this.image = image;
    }

    public String getStockStatus() {
        return stockStatus;
    }

    public void setStockStatus(String stockStatus) {
        this.stockStatus = stockStatus;
    }

    public static class Builder {
        private Long id;
        private String name;
        private String category;
        private BigDecimal price;
        private byte[] image;
        private String stockStatus;

        public Builder id(Long id) {
            this.id = id;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder category(String category) {
            this.category = category;
            return this;
        }

        public Builder price(BigDecimal price) {
            this.price = price;
            return this;
        }

        public Builder image(byte[] image) {
            this.image = image;
            return this;
        }

        public Builder stockStatus(String stockStatus) {
            this.stockStatus = stockStatus;
            return this;
        }

        public ProductCatalogVO build() {
            return new ProductCatalogVO(id, name, category, price, image, stockStatus);
        }
    }
}
