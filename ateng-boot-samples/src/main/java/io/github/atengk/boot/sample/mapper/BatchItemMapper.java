package io.github.atengk.boot.sample.mapper;

import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.boot.sample.entity.BatchItemEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 示例批量操作持久层 Mapper 接口
 * 继承 AtengBaseMapper 获得原生批量插入能力
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Mapper
public interface BatchItemMapper extends AtengBaseMapper<BatchItemEntity> {
}
