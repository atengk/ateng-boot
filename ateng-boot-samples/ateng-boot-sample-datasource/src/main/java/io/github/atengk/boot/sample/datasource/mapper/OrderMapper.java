package io.github.atengk.boot.sample.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.atengk.boot.sample.datasource.entity.OrderEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 订单数据访问层 Mapper
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Mapper
public interface OrderMapper extends BaseMapper<OrderEntity> {
}
