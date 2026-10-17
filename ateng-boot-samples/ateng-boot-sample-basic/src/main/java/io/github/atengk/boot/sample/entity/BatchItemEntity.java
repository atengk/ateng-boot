package io.github.atengk.boot.sample.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.atengk.boot.mybatisplus.entity.BaseFullEntity;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 示例批量操作实体对象
 * 映射 sys_batch_item 数据库表，用于验证原生高性能分批批量插入引擎
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("sys_batch_item")
public class BatchItemEntity extends BaseFullEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 项目名称
     */
    private String name;

    /**
     * 项目编码
     */
    private String code;
}
