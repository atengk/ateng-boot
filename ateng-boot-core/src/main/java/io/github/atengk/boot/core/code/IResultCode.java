package io.github.atengk.boot.core.code;

import java.io.Serializable;

/**
 * 业务响应状态码标准契约接口
 *
 * @author Ateng
 * @since 2026-10-15
 */
public interface IResultCode extends Serializable {

    /**
     * 获取业务状态码
     *
     * @return 状态码整数值
     */
    Integer getCode();

    /**
     * 获取状态描述信息
     *
     * @return 描述文案
     */
    String getMsg();
}
