package io.github.atengk.boot.sample.mybatisplus.mapper;

import io.github.atengk.boot.mybatisplus.annotation.IgnoreTenant;
import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.boot.sample.mybatisplus.entity.ProductSkuEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 商品 SKU 档案持久层 Mapper 接口
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Mapper
@IgnoreTenant
public interface ProductSkuMapper extends AtengBaseMapper<ProductSkuEntity> {
}
