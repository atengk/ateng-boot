package io.github.atengk.boot.mybatisplus.entity;

import java.io.Serializable;

/**
 * 强类型实体主键能力契约接口
 * 声明统一的主键读取与写入访问协议，支持雪花长整型、字符串、UUID 或自定义主键类型
 *
 * @param <ID> 主键类型
 * @author Ateng
 * @since 2026-11-20
 */
public interface Identifiable<ID extends Serializable> {

    /**
     * 获取实体主键 ID
     *
     * @return 主键 ID
     */
    ID getId();

    /**
     * 设置实体主键 ID
     *
     * @param id 主键 ID
     */
    void setId(ID id);
}
