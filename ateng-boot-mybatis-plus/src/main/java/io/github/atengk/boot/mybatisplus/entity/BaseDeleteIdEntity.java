package io.github.atengk.boot.mybatisplus.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 唯一索引防冲突受管业务持久层实体抽象基类
 * 在 BaseEntity 基础上扩展 CAS 乐观锁版本号与以自身主键 ID 动态占位的逻辑删除标记（delval = "id"），
 * 彻底消除业务组合唯一索引（如 uk_code_deleted(code, deleted)）在多次软删除场景下的 Duplicate Key 物理唯一键冲突。
 *
 * @author Ateng
 * @since 2026-10-18
 */
@Getter
@Setter
@ToString(callSuper = true)
public abstract class BaseDeleteIdEntity extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 乐观锁版本号
     */
    @Version
    private Integer version;

    /**
     * 逻辑删除标识（0: 未删除, 删除后动态更新为自身雪花主键 ID 值；标配 @JsonIgnore 防对外接口泄露）
     */
    @TableLogic(value = "0", delval = "id")
    @JsonIgnore
    private Long deleted;
}
