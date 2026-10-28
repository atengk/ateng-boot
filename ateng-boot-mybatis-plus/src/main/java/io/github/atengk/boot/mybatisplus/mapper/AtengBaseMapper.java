package io.github.atengk.boot.mybatisplus.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.cursor.Cursor;

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

    /**
     * 原生双方言单条多值批量插入或原地冲突更新（Upsert）
     * <p>
     * 底层自适应适配主流数据库方言：
     * <ul>
     *   <li>MySQL: 编译生成 {@code INSERT INTO ... ON DUPLICATE KEY UPDATE col1=VALUES(col1), ...}</li>
     *   <li>PostgreSQL: 编译生成 {@code INSERT INTO ... ON CONFLICT (conflict_cols) DO UPDATE SET col1=EXCLUDED.col1, ...}</li>
     * </ul>
     * 自动跳过主键 ID、创建时间与逻辑删除列。PostgreSQL 默认使用 {@code @TableId} 主键作为冲突键，支持通过 {@code @ConflictKeys} 实体注解自定义复合唯一键。
     * </p>
     *
     * @param entityList 实体集合
     * @return 成功操作影响的行数
     */
    int upsertBatchSomeColumn(@Param("list") Collection<T> entityList);

    /**
     * 原生单条多值 CASE WHEN 批量更新
     * <p>
     * 自动编译生成标准 ANSI SQL {@code UPDATE table SET col1 = CASE id WHEN ... ELSE col1 END WHERE id IN (...)} 单条高效 SQL，
     * 自动排除主键列、逻辑删除标记列与仅插入填充审计列，并在执行前无条件强制按主键升序排列以物理杜绝并发死锁。
     * </p>
     *
     * @param entityList 实体集合
     * @return 成功更新影响的行数
     */
    int updateBatchSomeColumn(@Param("list") Collection<T> entityList);

    /**
     * 原生单条冲突更新（Upsert）便捷门面
     * 自动跳过主键 ID、创建时间与逻辑删除列
     *
     * @param entity 实体对象
     * @return 成功操作影响的行数，实体为 null 时保底返回 0
     */
    default int upsertSomeColumn(T entity) {
        if (entity == null) {
            return 0;
        }
        return upsertBatchSomeColumn(java.util.Collections.singletonList(entity));
    }

    /**
     * 原生流式游标条件查询
     * <p>
     * 依托 MyBatis 原生 {@link Cursor} 与 JDBC 流式能力逐行读取海量数据，MappedStatement 强制注入 {@code fetchSize = 1000}，
     * 必须在事务上下文（如 {@link io.github.atengk.boot.mybatisplus.util.StreamingQueryTemplate} 或标注 {@code @Transactional} 的方法）中执行，
     * 并在消费完成后确定性关闭游标以释放底层 JDBC 物理连接。
     * </p>
     *
     * @param queryWrapper 实体对象封装操作类（可以为 null）
     * @return 实体游标
     */
    Cursor<T> selectCursor(@Param(Constants.WRAPPER) Wrapper<T> queryWrapper);
}
