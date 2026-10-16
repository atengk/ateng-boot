package io.github.atengk.boot.mybatisplus.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;

/**
 * 增强型通用持久层 Mapper 接口
 * 继承 MyBatis-Plus BaseMapper，扩展原生高性能单条多值批量插入方法
 *
 * @param <T> 实体类型
 * @author Ateng
 * @since 2026-10-16
 */
public interface AtengBaseMapper<T> extends BaseMapper<T> {

    /**
     * 原生单条多值批量插入（INSERT INTO table (col1, col2) VALUES (...), (...)）
     * 自动跳过逻辑删除标记字段
     * <p>
     * 注意：若集合元素量极大（如 > 1000 条），建议使用 AtengBaseService 或 BatchUtil 进行分批切片插入，防止超出数据库 max_allowed_packet
     * </p>
     *
     * @param entityList 实体集合
     * @return 成功插入影响的行数
     */
    int insertBatchSomeColumn(@Param("list") Collection<T> entityList);
}
