package io.github.atengk.boot.mybatisplus.dynamicsql;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 供动态 SQL 声明式 Mapper 绑定测试使用的测试接口
 *
 * @author Ateng
 * @since 2026-10-18
 */
public interface TestDynamicUserMapper {

    /**
     * 无本地 XML 与注解的抽象方法，由外部动态 SQL 提供 MappedStatement
     */
    List<Map<String, Object>> findUsers(@Param("name") String name);

    /**
     * 动态更新测试
     */
    int updateStatus(@Param("id") Long id, @Param("status") Integer status);
}
