package io.github.atengk.boot.sample.smoke.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.atengk.boot.sample.smoke.entity.SmokeRecordEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 冒烟集成测试 Mapper 接口
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Mapper
public interface SmokeRecordMapper extends BaseMapper<SmokeRecordEntity> {
}
