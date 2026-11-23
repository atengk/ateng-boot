package io.github.atengk.boot.mybatisplus.injector.methods;

import com.baomidou.mybatisplus.core.enums.SqlMethod;
import com.baomidou.mybatisplus.core.injector.methods.DeleteByIds;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlSource;

/**
 * 原生物理硬删除 SQL 注入器节点（按主键集合批量物理删除）
 * 无视实体是否标注 @TableLogic 逻辑删除注解，始终生成物理 DELETE FROM table WHERE id IN (...) 语句
 *
 * @author Ateng
 * @since 2026-11-09
 */
public class DeletePhysicallyBatchByIds extends DeleteByIds {

    /**
     * 默认方法名
     */
    public static final String METHOD_NAME = "deletePhysicallyBatchByIds";

    public DeletePhysicallyBatchByIds() {
        this(METHOD_NAME);
    }

    public DeletePhysicallyBatchByIds(String name) {
        super(name);
    }

    @Override
    public MappedStatement injectMappedStatement(Class<?> mapperClass, Class<?> modelClass, TableInfo tableInfo) {
        if (!tableInfo.havePK()) {
            return null;
        }
        SqlMethod sqlMethod = SqlMethod.DELETE_BY_IDS;
        String sql = sqlMethod.format(tableInfo.getTableName(), tableInfo.getKeyColumn(), getConvertForeachScript(tableInfo));
        SqlSource sqlSource = super.createSqlSource(configuration, sql, Object.class);
        return addDeleteMappedStatement(mapperClass, this.methodName, sqlSource);
    }
}
