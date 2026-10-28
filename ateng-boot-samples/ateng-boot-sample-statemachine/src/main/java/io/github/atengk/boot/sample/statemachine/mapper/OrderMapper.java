package io.github.atengk.boot.sample.statemachine.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.atengk.boot.sample.statemachine.entity.OrderDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 订单数据访问 Mapper 接口
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Mapper
public interface OrderMapper extends BaseMapper<OrderDO> {

    /**
     * 原子 CAS 乐观锁条件更新状态
     *
     * @param id           订单 ID
     * @param expectStatus 期望源状态
     * @param targetStatus 目标新状态
     * @param version      乐观锁版本号
     * @return 实际影响行数（1 代表 CAS 成功，0 代表并发冲突或已被其他事务更改）
     */
    @Update("UPDATE t_order SET status = #{targetStatus}, version = version + 1, updated_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status = #{expectStatus} AND version = #{version}")
    int updateStatusWithCas(@Param("id") Long id,
                            @Param("expectStatus") String expectStatus,
                            @Param("targetStatus") String targetStatus,
                            @Param("version") Integer version);

    /**
     * 更新订单物流追踪信息（用于内部自跃迁更新物理字段而不改变主状态）
     *
     * @param id         订单 ID
     * @param trackingNo 运单号
     * @param remark     物流动态说明
     * @return 实际影响行数
     */
    @Update("UPDATE t_order SET tracking_no = #{trackingNo}, tracking_remark = #{remark}, updated_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id}")
    int updateTracking(@Param("id") Long id,
                       @Param("trackingNo") String trackingNo,
                       @Param("remark") String remark);
}
