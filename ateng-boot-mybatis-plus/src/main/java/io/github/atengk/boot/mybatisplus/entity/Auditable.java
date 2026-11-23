package io.github.atengk.boot.mybatisplus.entity;

/**
 * 四要素全审计能力契约接口
 * 继承时间审计契约并追加创建人与修改人读写访问方法，供审计填充器与批量自愈引擎无反射调用
 *
 * @author Ateng
 * @since 2026-11-20
 */
public interface Auditable extends TimeAuditable {

    /**
     * 获取创建人唯一标识
     *
     * @return 创建人标识
     */
    String getCreateBy();

    /**
     * 设置创建人唯一标识
     *
     * @param createBy 创建人标识
     */
    void setCreateBy(String createBy);

    /**
     * 获取修改人唯一标识
     *
     * @return 修改人标识
     */
    String getUpdateBy();

    /**
     * 设置修改人唯一标识
     *
     * @param updateBy 修改人标识
     */
    void setUpdateBy(String updateBy);
}
