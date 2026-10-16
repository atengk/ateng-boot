package io.github.atengk.boot.sample.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.atengk.boot.mybatisplus.entity.BaseIdEntity;

/**
 * 公共字典测试实体（免多租户过滤）
 *
 * @author Ateng
 * @since 2026-10-16
 */
@TableName("sys_dict")
public class SysDictEntity extends BaseIdEntity {

    private String dictKey;

    private String dictValue;

    public String getDictKey() {
        return dictKey;
    }

    public void setDictKey(String dictKey) {
        this.dictKey = dictKey;
    }

    public String getDictValue() {
        return dictValue;
    }

    public void setDictValue(String dictValue) {
        this.dictValue = dictValue;
    }
}
