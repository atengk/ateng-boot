package io.github.atengk.boot.mybatisplus.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 全要素受管业务持久层实体抽象基类
 * 在 BaseEntity 基础上追加 CAS 乐观锁版本号与在 JSON 中自动脱敏忽略的逻辑删除标记，专供核心状态流转表
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Getter
@Setter
@ToString(callSuper = true)
public abstract class BaseFullEntity extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 乐观锁版本号
     */
    @Version
    private Integer version;

    /**
     * 逻辑删除标识（0: 未删除, 1: 已删除；标配 @JsonIgnore 防对外接口泄露）
     */
    @TableLogic
    @JsonIgnore
    private Integer deleted;
}
