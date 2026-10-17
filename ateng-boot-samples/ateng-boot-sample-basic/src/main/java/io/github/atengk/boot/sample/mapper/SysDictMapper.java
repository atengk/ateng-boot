package io.github.atengk.boot.sample.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.atengk.boot.sample.entity.SysDictEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 公共字典 Mapper 接口
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Mapper
public interface SysDictMapper extends BaseMapper<SysDictEntity> {
}
