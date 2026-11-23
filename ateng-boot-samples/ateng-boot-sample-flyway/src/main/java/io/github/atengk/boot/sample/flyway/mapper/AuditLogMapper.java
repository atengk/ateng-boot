package io.github.atengk.boot.sample.flyway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.atengk.boot.sample.flyway.entity.AuditLogEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 独立审计日志库 Mapper
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLogEntity> {
}
