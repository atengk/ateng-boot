package io.github.atengk.boot.sample.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.atengk.boot.mybatisplus.annotation.DataScope;
import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.boot.sample.entity.SysDocumentEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 业务文档 Mapper 接口
 * 声明标注各种细粒度 @DataScope 数据权限注解查询方法
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Mapper
public interface SysDocumentMapper extends AtengBaseMapper<SysDocumentEntity> {

    /**
     * 单表查询：标注 @DataScope 进行细粒度数据权限过滤
     *
     * @return 过滤后的文档列表
     */
    @DataScope(deptColumn = "dept_id", userColumn = "user_id")
    @Select("SELECT * FROM sys_document")
    List<SysDocumentEntity> selectListWithScope();

    /**
     * 分页查询：标注 @DataScope 进行细粒度数据权限过滤（验证 count 语句同步改写）
     *
     * @param page 分页参数
     * @return 过滤后的分页结果
     */
    @DataScope(deptColumn = "dept_id", userColumn = "user_id")
    @Select("SELECT * FROM sys_document")
    Page<SysDocumentEntity> selectPageWithScope(Page<SysDocumentEntity> page);

    /**
     * 多表关联 JOIN 查询：标注 @DataScope(alias = "d") 验证表别名精准匹配注入
     *
     * @return 多表关联过滤后的记录
     */
    @DataScope(alias = "d", deptColumn = "dept_id", userColumn = "user_id")
    @Select("SELECT d.id, d.title, d.dept_id, d.user_id, u.name AS user_name " +
            "FROM sys_document d LEFT JOIN sys_user u ON d.user_id = u.id")
    List<Map<String, Object>> selectJoinWithScope();

    /**
     * 普通查询：未标注 @DataScope，验证秒级短路放行，全量查询
     *
     * @return 全量文档列表
     */
    @Select("SELECT * FROM sys_document")
    List<SysDocumentEntity> selectNormal();

    /**
     * 显式豁免查询：标注 @DataScope(ignore = true)，验证豁免短路放行
     *
     * @return 全量文档列表
     */
    @DataScope(ignore = true)
    @Select("SELECT * FROM sys_document")
    List<SysDocumentEntity> selectExempt();
}
