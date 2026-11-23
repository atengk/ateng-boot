package io.github.atengk.boot.sample.mybatisplus.model;

import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.mybatisplus.dynamicquery.QueryOperator;
import io.github.atengk.boot.mybatisplus.dynamicquery.annotation.QueryField;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 系统用户声明式动态检索载荷对象
 * 继承标准分页契约 PageQuery，通过 @QueryField 注解声明目标实体属性映射与受控查询操作符
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserSearchDTO extends PageQuery {

    private static final long serialVersionUID = 1L;

    /**
     * 用户姓名（模糊匹配）
     */
    @QueryField(operator = QueryOperator.LIKE)
    private String name;

    /**
     * 最小年龄（大于等于 age >= minAge）
     */
    @QueryField(property = "age", operator = QueryOperator.GE)
    private Integer minAge;

    /**
     * 最大年龄（小于等于 age <= maxAge）
     */
    @QueryField(property = "age", operator = QueryOperator.LE)
    private Integer maxAge;

    /**
     * 所属部门 ID 集合（IN 匹配）
     */
    @QueryField(property = "deptId", operator = QueryOperator.IN)
    private List<Long> deptIds;

    /**
     * 部门名称（多表联查时通过 AST 投影字典自动映射为 d.dept_name）
     */
    @QueryField(operator = QueryOperator.LIKE)
    private String deptName;

    /**
     * 电子邮箱（确定性加密字段精确等值检索）
     */
    @QueryField(property = "email", operator = QueryOperator.EQ)
    private String email;

    /**
     * 内部风控备注（用于验证目标实体标注 @DynamicQueryIgnore 时的越权过滤拦截）
     */
    @QueryField(property = "internalRemark", operator = QueryOperator.EQ)
    private String internalRemark;
}
