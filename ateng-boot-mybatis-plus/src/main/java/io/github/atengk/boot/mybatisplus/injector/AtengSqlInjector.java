package io.github.atengk.boot.mybatisplus.injector;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.injector.AbstractMethod;
import com.baomidou.mybatisplus.core.injector.DefaultSqlInjector;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.extension.injector.methods.InsertBatchSomeColumn;
import io.github.atengk.boot.mybatisplus.injector.methods.SelectCursor;
import io.github.atengk.boot.mybatisplus.injector.methods.UpdateBatchSomeColumn;
import io.github.atengk.boot.mybatisplus.injector.methods.UpsertBatchSomeColumn;
import org.apache.ibatis.session.Configuration;

import java.util.List;

/**
 * 增强型全局 SQL 注入器
 * 继承 MyBatis-Plus DefaultSqlInjector，默认挂载 InsertBatchSomeColumn、UpsertBatchSomeColumn、UpdateBatchSomeColumn 与 SelectCursor 原生方法，
 * 自动排除标注 @TableLogic 的逻辑删除字段与仅插入填充字段，支持 MySQL / PostgreSQL 双方言原生 Upsert 语法、单条 CASE WHEN 防死锁批量更新与受管游标流式查询
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class AtengSqlInjector extends DefaultSqlInjector {

    private final DbType dbType;

    public AtengSqlInjector() {
        this(null);
    }

    public AtengSqlInjector(DbType dbType) {
        this.dbType = dbType;
    }

    @Override
    public List<AbstractMethod> getMethodList(Configuration configuration, Class<?> mapperClass, TableInfo tableInfo) {
        List<AbstractMethod> methodList = super.getMethodList(configuration, mapperClass, tableInfo);
        // 1. 挂载原生多值批量插入方法：INSERT INTO table (col1, col2) VALUES (...), (...)
        // 传入 Predicate 过滤逻辑删除字段
        methodList.add(new InsertBatchSomeColumn(field -> !field.isLogicDelete()));
        // 2. 挂载原生双方言多值批量 Upsert 方法：INSERT INTO table (...) VALUES (...) ON DUPLICATE KEY / ON CONFLICT
        methodList.add(new UpsertBatchSomeColumn(field -> !field.isLogicDelete(), dbType));
        // 3. 挂载原生单条多值 CASE WHEN 批量更新方法：UPDATE table SET col1 = CASE id WHEN ... ELSE col1 END WHERE id IN (...)
        methodList.add(new UpdateBatchSomeColumn(field -> !field.isLogicDelete()));
        // 4. 挂载原生受管游标查询方法：SELECT ... FROM table <where> ... (fetchSize = 1000)
        methodList.add(new SelectCursor());
        return methodList;
    }

    /**
     * 判断字段是否为仅插入填充字段（如 create_time / create_by 或 FieldFill.INSERT）
     * 批量更新或冲突更新时不应覆盖此类审计字段
     *
     * @param field MyBatis-Plus 字段元数据信息
     * @return true 若为仅插入字段；否则 false
     */
    public static boolean isCreateOnlyField(com.baomidou.mybatisplus.core.metadata.TableFieldInfo field) {
        if (field == null) {
            return false;
        }
        if (field.getFieldFill() == com.baomidou.mybatisplus.annotation.FieldFill.INSERT) {
            return true;
        }
        String prop = field.getProperty();
        return "createTime".equalsIgnoreCase(prop) || "createBy".equalsIgnoreCase(prop) || "gmtCreate".equalsIgnoreCase(prop);
    }
}
