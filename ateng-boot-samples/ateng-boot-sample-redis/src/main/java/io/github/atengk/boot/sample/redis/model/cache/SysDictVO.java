package io.github.atengk.boot.sample.redis.model.cache;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 系统字典单级缓存视图对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SysDictVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String dictCode;
    private String dictName;
    private String dictValue;
    private Integer sortOrder;
}
