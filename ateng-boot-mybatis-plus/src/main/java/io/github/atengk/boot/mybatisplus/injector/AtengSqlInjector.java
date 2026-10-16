package io.github.atengk.boot.mybatisplus.injector;

import com.baomidou.mybatisplus.core.injector.AbstractMethod;
import com.baomidou.mybatisplus.core.injector.DefaultSqlInjector;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.extension.injector.methods.InsertBatchSomeColumn;
import org.apache.ibatis.session.Configuration;

import java.util.List;

/**
 * 增强型全局 SQL 注入器
 * 继承 MyBatis-Plus DefaultSqlInjector，默认挂载 InsertBatchSomeColumn 原生批量插入方法，
 * 自动排除标注 @TableLogic 的逻辑删除字段（field.isLogicDelete()），避免批量插入新记录时被强行赋默认删除标记
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class AtengSqlInjector extends DefaultSqlInjector {

    @Override
    public List<AbstractMethod> getMethodList(Configuration configuration, Class<?> mapperClass, TableInfo tableInfo) {
        List<AbstractMethod> methodList = super.getMethodList(configuration, mapperClass, tableInfo);
        // 挂载原生多值批量插入方法：INSERT INTO table (col1, col2) VALUES (...), (...)
        // 传入 Predicate 过滤逻辑删除字段
        methodList.add(new InsertBatchSomeColumn(field -> !field.isLogicDelete()));
        return methodList;
    }
}
