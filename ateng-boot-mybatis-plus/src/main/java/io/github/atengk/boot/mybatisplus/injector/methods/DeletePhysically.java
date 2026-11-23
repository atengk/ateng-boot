package io.github.atengk.boot.mybatisplus.injector.methods;

import com.baomidou.mybatisplus.core.enums.SqlMethod;
import com.baomidou.mybatisplus.core.injector.AbstractMethod;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.toolkit.sql.SqlScriptUtils;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlSource;

/**
 * 原生物理硬删除 SQL 注入器节点（按 Wrapper 条件物理删除）
 * 无视实体是否标注 @TableLogic 逻辑删除注解，始终生成物理 DELETE FROM table &lt;where&gt; ... 语句
 *
 * @author Ateng
 * @since 2026-11-09
 */
public class DeletePhysically extends AbstractMethod {

    /**
     * 默认方法名
     */
    public static final String METHOD_NAME = "deletePhysically";

    public DeletePhysically() {
        this(METHOD_NAME);
    }

    public DeletePhysically(String name) {
        super(name);
    }

    @Override
    public MappedStatement injectMappedStatement(Class<?> mapperClass, Class<?> modelClass, TableInfo tableInfo) {
        SqlMethod sqlMethod = SqlMethod.DELETE;
        String sql = sqlMethod.format(
                tableInfo.getTableName(),
                sqlWhereEntityWrapperWithoutLogicDelete(tableInfo),
                sqlComment()
        );
        SqlSource sqlSource = super.createSqlSource(configuration, sql, modelClass);
        return addDeleteMappedStatement(mapperClass, this.methodName, sqlSource);
    }

    /**
     * 构造不包含逻辑未删除条件的 WHERE 子句语法树片段，确保物理硬删除能够彻底清除回收站已逻辑删除的记录
     *
     * @param tableInfo 表元数据信息
     * @return 不包含逻辑删除条件的 WHERE 条件 SQL 脚本片段
     */
    protected String sqlWhereEntityWrapperWithoutLogicDelete(TableInfo tableInfo) {
        String sqlScript = tableInfo.getAllSqlWhere(false, false, true, "ew.entity.");
        sqlScript = SqlScriptUtils.convertIf(sqlScript, "ew.entity != null", true);
        String andSqlSegment = SqlScriptUtils.convertIf(" AND ${ew.sqlSegment}", "_sgEs_ and ew.nonEmptyOfNormal", true);
        String lastSqlSegment = SqlScriptUtils.convertIf(" ${ew.sqlSegment}", "_sgEs_ and ew.emptyOfNormal", true);
        sqlScript = SqlScriptUtils.convertWhere(sqlScript + "\n" + andSqlSegment) + "\n" + lastSqlSegment;
        sqlScript = SqlScriptUtils.convertIf("<bind name=\"_sgEs_\" value=\"ew.sqlSegment != null and ew.sqlSegment != ''\"/>\n" + sqlScript, "ew != null", true);
        return "\n" + sqlScript;
    }
}
