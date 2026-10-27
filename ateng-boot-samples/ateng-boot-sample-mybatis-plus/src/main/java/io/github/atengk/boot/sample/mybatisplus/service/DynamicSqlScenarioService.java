package io.github.atengk.boot.sample.mybatisplus.service;

import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.mybatisplus.dynamicsql.DynamicSqlDefinition;
import io.github.atengk.boot.mybatisplus.dynamicsql.DynamicSqlExecutor;
import io.github.atengk.boot.mybatisplus.dynamicsql.DynamicSqlRegistry;
import io.github.atengk.boot.mybatisplus.dynamicsql.ResourceExternalSqlLoader;
import io.github.atengk.boot.sample.mybatisplus.model.UserDynamicVO;

import java.util.List;
import java.util.Map;

/**
 * 外部动态 SQL 体系业务场景服务契约
 * 演示外部 XML 动态 SQL 扫描加载、免 Mapper 执行、运行时热注册/热刷新与分页契约
 *
 * @author Ateng
 * @since 2026-10-27
 */
public interface DynamicSqlScenarioService {

    /**
     * 免 Mapper 接口调用动态 SQL 执行多条件用户列表查询
     *
     * @param name   用户名称（模糊匹配）
     * @param minAge 最小年龄
     * @param deptId 部门 ID
     * @return 用户强类型 VO 列表
     */
    List<UserDynamicVO> findUsersDynamic(String name, Integer minAge, Long deptId);

    /**
     * 免 Mapper 接口调用动态 SQL 执行带参流式分页查询
     *
     * @param name      用户名称（模糊匹配）
     * @param minAge    最小年龄
     * @param pageQuery 分页参数
     * @return 分页结果 PageResult<UserDynamicVO>
     */
    PageResult<UserDynamicVO> findUsersDynamicPage(String name, Integer minAge, PageQuery pageQuery);

    /**
     * 免 Mapper 接口执行动态统计
     *
     * @param name   用户名称（模糊匹配）
     * @param minAge 最小年龄
     * @param deptId 部门 ID
     * @return 符合条件的总记录数
     */
    long countUsersDynamic(String name, Integer minAge, Long deptId);

    /**
     * 运行时动态注册自定义 SQL 片段
     *
     * @param definition 动态 SQL 定义
     * @return 是否成功注册
     */
    boolean registerCustomSql(DynamicSqlDefinition definition);

    /**
     * 运行时热替换已有 SQL 语句
     *
     * @param definition 新的动态 SQL 定义
     * @return 是否成功热替换
     */
    boolean replaceCustomSql(DynamicSqlDefinition definition);

    /**
     * 卸载/移除已注册的动态 SQL 语句
     *
     * @param statementId 语句唯一标识
     * @return 是否成功移除
     */
    boolean removeCustomSql(String statementId);

    /**
     * 执行自定义动态 SQL 查询并返回 Map 结构
     *
     * @param statementId 语句标识
     * @param params      入参
     * @return 结果列表
     */
    List<Map<String, Object>> executeCustomQuery(String statementId, Map<String, Object> params);

    /**
     * 获取已注入的 DynamicSqlRegistry
     *
     * @return 动态 SQL 注册中心
     */
    DynamicSqlRegistry getRegistry();

    /**
     * 获取已注入的 DynamicSqlExecutor
     *
     * @return 动态 SQL 执行门面
     */
    DynamicSqlExecutor getExecutor();

    /**
     * 获取已注入的 ResourceExternalSqlLoader
     *
     * @return 外部 SQL 资源加载器
     */
    ResourceExternalSqlLoader getLoader();
}
