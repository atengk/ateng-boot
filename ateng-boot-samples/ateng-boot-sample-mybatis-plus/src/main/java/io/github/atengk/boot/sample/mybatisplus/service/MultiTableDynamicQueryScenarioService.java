package io.github.atengk.boot.sample.mybatisplus.service;

import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryRequest;
import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryTemplate;
import io.github.atengk.boot.mybatisplus.service.AtengBaseService;
import io.github.atengk.boot.sample.mybatisplus.entity.UserEntity;
import io.github.atengk.boot.sample.mybatisplus.model.UserDeptDetailVO;

/**
 * 多表联查动态 AST 条件推导与别名自愈业务场景服务契约
 * 演示多表联查下通过 JSqlParser AST 分析将 VO 属性名与多表别名透明映射为底层物理列（如 deptName -> d.dept_name），
 * 并依托方法引用与 DynamicQueryTemplate 执行动态流式分页与单页短路存在性判定。
 *
 * @author Ateng
 * @since 2026-10-28
 */
public interface MultiTableDynamicQueryScenarioService extends AtengBaseService<UserEntity> {

    /**
     * 基于结构化 DynamicQueryRequest 执行多表联查分页（依托 AtengBaseService.pageDynamic 与方法引用）
     *
     * @param request 动态查询请求
     * @return 分页结果 PageResult<UserDeptDetailVO>
     */
    PageResult<UserDeptDetailVO> pageUserDeptByMethodRef(DynamicQueryRequest request);

    /**
     * 基于 StatementId 与结构化 DynamicQueryRequest 执行多表联查分页（依托 DynamicQueryTemplate）
     *
     * @param request 动态查询请求
     * @return 分页结果 PageResult<UserDeptDetailVO>
     */
    PageResult<UserDeptDetailVO> pageUserDeptByTemplate(DynamicQueryRequest request);

    /**
     * 基于方法引用执行多表存在性判断（利用单页 Page(1, 1, false) 物理短路判定机制）
     *
     * @param request 动态查询请求
     * @return 是否存在满足条件的记录
     */
    boolean existsUserDeptByMethodRef(DynamicQueryRequest request);

    /**
     * 基于 StatementId 执行多表存在性判定（利用 DynamicQueryTemplate 短路判定机制）
     *
     * @param request 动态查询请求
     * @return 是否存在满足条件的记录
     */
    boolean existsUserDeptByTemplate(DynamicQueryRequest request);

    /**
     * 获取受管 DynamicQueryTemplate 实例
     *
     * @return 动态查询模板
     */
    DynamicQueryTemplate getDynamicQueryTemplate();
}
