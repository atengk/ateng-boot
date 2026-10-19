package io.github.atengk.boot.mybatisplus.dynamicquery;

import com.baomidou.mybatisplus.core.conditions.Wrapper;

import java.io.Serializable;
import java.util.List;

/**
 * MyBatis-Plus 自定义列表 Mapper 方法引用契约接口
 * 继承 Serializable 以支持方法引用反射推导方法名与 MappedStatement 定位
 *
 * @param <V> 目标视图或实体泛型
 * @author Ateng
 * @since 2026-10-19
 */
@FunctionalInterface
public interface DynamicMapperListMethod<V> extends Serializable {

    /**
     * 执行自定义 Mapper 列表查询方法
     *
     * @param wrapper 包含动态条件与排序的 Wrapper
     * @return 列表结果集合
     */
    List<V> select(Wrapper<V> wrapper);
}
