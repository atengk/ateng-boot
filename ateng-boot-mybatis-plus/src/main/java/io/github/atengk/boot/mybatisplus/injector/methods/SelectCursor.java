package io.github.atengk.boot.mybatisplus.injector.methods;

import com.baomidou.mybatisplus.core.enums.SqlMethod;
import com.baomidou.mybatisplus.core.injector.AbstractMethod;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlSource;

import java.lang.reflect.Field;

/**
 * 原生受管游标查询 SQL 注入器节点
 * <p>
 * 注入与 {@code selectList} 一致的条件查询 SQL 语法树，
 * 并在 {@link MappedStatement} 中显式配置 {@code fetchSize = 1000}，
 * 配合底层 JDBC 驱动流式模式与 MyBatis 原生 {@link org.apache.ibatis.cursor.Cursor}，
 * 实现低内存占用的高效流式拉取。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Getter
@Setter
@Accessors(chain = true)
public class SelectCursor extends AbstractMethod {

    /**
     * 默认方法名
     */
    public static final String METHOD_NAME = "selectCursor";

    /**
     * 默认 JDBC fetchSize 批次大小（1000）
     */
    public static final int DEFAULT_FETCH_SIZE = 1000;

    /**
     * JDBC fetchSize 大小
     */
    private int fetchSize;

    public SelectCursor() {
        this(METHOD_NAME, DEFAULT_FETCH_SIZE);
    }

    public SelectCursor(int fetchSize) {
        this(METHOD_NAME, fetchSize);
    }

    public SelectCursor(String name, int fetchSize) {
        super(name);
        this.fetchSize = fetchSize > 0 ? fetchSize : DEFAULT_FETCH_SIZE;
    }

    @Override
    public MappedStatement injectMappedStatement(Class<?> mapperClass, Class<?> modelClass, TableInfo tableInfo) {
        SqlMethod sqlMethod = SqlMethod.SELECT_LIST;
        String sql = sqlMethod.format(sqlFirst(), sqlSelectColumns(tableInfo, true), tableInfo.getTableName(),
                sqlWhereEntityWrapper(true, tableInfo), sqlOrderBy(tableInfo), sqlComment());
        SqlSource sqlSource = super.createSqlSource(configuration, sql, modelClass);
        MappedStatement ms = addSelectMappedStatementForTable(mapperClass, methodName, sqlSource, tableInfo);
        if (ms != null) {
            setFetchSize(ms, this.fetchSize);
        }
        return ms;
    }

    private static void setFetchSize(MappedStatement ms, int fetchSize) {
        try {
            Field field = MappedStatement.class.getDeclaredField("fetchSize");
            field.setAccessible(true);
            field.set(ms, fetchSize);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to set fetchSize on MappedStatement: " + ms.getId(), e);
        }
    }
}
