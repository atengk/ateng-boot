package io.github.atengk.boot.liteflow.component;

import com.yomahub.liteflow.core.NodeForComponent;
import io.github.atengk.boot.core.util.JsonUtil;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * LiteFlow 泛型受管 FOR 循环计数组件基类
 * 自动绑定业务强类型上下文，业务子类直接实现 {@link #processFor(Object)} 返回循环次数
 *
 * @param <T> 业务上下文类型
 * @author Ateng
 * @since 2026-11-03
 */
public abstract class BaseForComponent<T> extends NodeForComponent {

    private final Class<T> contextClass;

    protected BaseForComponent() {
        this.contextClass = ComponentContextResolver.resolveGenericContextClass(getClass(), BaseForComponent.class);
    }

    /**
     * 获取绑定的业务强类型上下文实例
     *
     * @return 业务上下文实例
     * @throws IllegalStateException 若当前执行槽中未找到对应上下文
     */
    public T getContext() {
        if (this.contextClass != null) {
            T ctx = this.getContextBean(this.contextClass);
            if (ctx != null) {
                return ctx;
            }
        }
        T firstCtx = this.getFirstContextBean();
        if (firstCtx != null) {
            return firstCtx;
        }
        String targetName = this.contextClass != null ? this.contextClass.getName() : "Context";
        throw new IllegalStateException("未能从当前执行槽中获取到类型为 [" + targetName + "] 的业务上下文实例，请检查流程执行入参");
    }

    /**
     * 执行 FOR 循环次数决策
     *
     * @param context 强类型业务上下文
     * @return 目标循环执行次数
     * @throws Exception 业务执行异常
     */
    public abstract int processFor(T context) throws Exception;

    @Override
    public int processFor() throws Exception {
        return processFor(getContext());
    }

    /**
     * 是否允许执行当前循环节点（带上下文重载，默认 true）
     *
     * @param context 强类型业务上下文
     * @return true 表示执行，false 表示跳过
     */
    public boolean isAccess(T context) {
        return true;
    }

    @Override
    public boolean isAccess() {
        try {
            return isAccess(getContext());
        } catch (Throwable e) {
            return super.isAccess();
        }
    }

    @Override
    public String getTag() {
        return this.getRefNode() != null ? this.getRefNode().getTag() : null;
    }

    /**
     * 将节点配置的 cmpData JSON 反序列化为强类型 Bean 或 Record
     *
     * @param dataClass 目标配置数据 Class
     * @param <D>       配置数据泛型
     * @return 反序列化后的配置对象，若无配置数据则返回 null
     */
    public <D> D getCmpData(Class<D> dataClass) {
        Objects.requireNonNull(dataClass, "dataClass 不能为空");
        String cmpData = this.getRefNode() != null ? this.getRefNode().getCmpData() : null;
        if (cmpData == null || cmpData.isBlank()) {
            return null;
        }
        if (String.class.equals(dataClass) || Object.class.equals(dataClass)) {
            @SuppressWarnings("unchecked")
            D raw = (D) cmpData;
            return raw;
        }
        return JsonUtil.parseObject(cmpData, dataClass);
    }

    /**
     * 将节点配置的 cmpData JSON 数组反序列化为强类型列表
     *
     * @param elementClass 列表元素 Class
     * @param <D>          元素泛型
     * @return 反序列化后的列表，若无数据返回空列表
     */
    public <D> List<D> getCmpDataList(Class<D> elementClass) {
        Objects.requireNonNull(elementClass, "elementClass 不能为空");
        String cmpData = this.getRefNode() != null ? this.getRefNode().getCmpData() : null;
        if (cmpData == null || cmpData.isBlank()) {
            return Collections.emptyList();
        }
        return JsonUtil.parseList(cmpData, elementClass);
    }
}
