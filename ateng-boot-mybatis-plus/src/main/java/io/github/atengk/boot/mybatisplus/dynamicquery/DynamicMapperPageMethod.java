package io.github.atengk.boot.mybatisplus.dynamicquery;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;

import java.io.Serializable;

/**
 * MyBatis-Plus 自定义分页 Mapper 方法引用契约接口
 * 继承 Serializable 以支持方法引用反射推导方法名与 MappedStatement 定位
 *
 * @param <V> 目标视图或实体泛型
 * @author Ateng
 * @since 2026-10-19
 */
@FunctionalInterface
public interface DynamicMapperPageMethod<V> extends Serializable {

    /**
     * 执行自定义 Mapper 分页查询方法
     *
     * @param page    MyBatis-Plus 分页对象
     * @param wrapper 包含动态条件与排序的 Wrapper
     * @return 分页结果 IPage 实例
     */
    IPage<V> select(IPage<V> page, Wrapper<V> wrapper);
}
