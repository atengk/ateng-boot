package io.github.atengk.boot.mybatisplus.injector;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.injector.AbstractMethod;
import com.baomidou.mybatisplus.core.injector.DefaultSqlInjector;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.extension.injector.methods.InsertBatchSomeColumn;
import io.github.atengk.boot.mybatisplus.injector.methods.UpsertBatchSomeColumn;
import org.apache.ibatis.session.Configuration;

import java.util.List;

/**
 * 增强型全局 SQL 注入器
 * 继承 MyBatis-Plus DefaultSqlInjector，默认挂载 InsertBatchSomeColumn 与 UpsertBatchSomeColumn 原生多值批量操作，
 * 自动排除标注 @TableLogic 的逻辑删除字段与仅插入填充字段，并支持 MySQL / PostgreSQL 双方言原生 Upsert 语法
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
        return methodList;
    }
}
