package io.github.atengk.boot.mybatisplus.entity;

import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

/**
 * 泛型主键全要素受管持久层实体抽象基类
 * 在 BaseGenericLogicEntity 基础上实现 Versioned 契约，追加 CAS 乐观锁版本号
 *
 * @param <ID> 主键及逻辑删除占位类型
 * @author Ateng
 * @since 2026-11-20
 */
@Getter
@Setter
@ToString(callSuper = true)
public abstract class BaseGenericFullEntity<ID extends Serializable> extends BaseGenericLogicEntity<ID> implements Versioned {

    private static final long serialVersionUID = 1L;

    /**
     * CAS 乐观锁版本号
     */
    @Version
    private Integer version;
}
