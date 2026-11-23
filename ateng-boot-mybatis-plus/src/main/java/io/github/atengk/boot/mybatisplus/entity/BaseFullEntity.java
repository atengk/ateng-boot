package io.github.atengk.boot.mybatisplus.entity;

import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 全要素受管业务持久层实体抽象基类
 * 在 BaseLogicEntity 基础上实现 Versioned 契约，追加 CAS 乐观锁版本号，专供高并发状态流转表
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Getter
@Setter
@ToString(callSuper = true)
public abstract class BaseFullEntity extends BaseLogicEntity implements Versioned {

    private static final long serialVersionUID = 1L;

    /**
     * 乐观锁版本号
     */
    @Version
    private Integer version;
}
