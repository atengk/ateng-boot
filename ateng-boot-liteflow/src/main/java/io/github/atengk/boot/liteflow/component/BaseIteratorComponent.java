package io.github.atengk.boot.liteflow.component;

import com.yomahub.liteflow.core.NodeIteratorComponent;
import io.github.atengk.boot.core.util.JsonUtil;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * LiteFlow 泛型受管 ITERATOR 迭代器循环组件基类
 * 自动绑定业务强类型上下文，业务子类直接实现 {@link #processIterator(Object)} 返回要迭代的迭代器对象
 *
 * @param <T> 业务上下文类型
 * @author Ateng
 * @since 2026-11-03
 */
public abstract class BaseIteratorComponent<T> extends NodeIteratorComponent {

    private final Class<T> contextClass;

    protected BaseIteratorComponent() {
        this.contextClass = ComponentContextResolver.resolveGenericContextClass(getClass(), BaseIteratorComponent.class);
    }

    /**
     * 获取绑定的业务强类型上下文实例
     *
     * @return 业务上下文实例
     * @throws IllegalStateException 若当前执行槽中未找到对应上下文
     */
    public T getContext() {
        return ComponentSupport.resolveContext(this, this.contextClass);
    }

    /**
     * 执行 ITERATOR 迭代器构造与提取
     *
     * @param context 强类型业务上下文
     * @return 业务待迭代的 Iterator 实例
     * @throws Exception 业务执行异常
     */
    public abstract Iterator<?> processIterator(T context) throws Exception;

    @Override
    public Iterator<?> processIterator() throws Exception {
        return processIterator(getContext());
    }

    /**
     * 是否允许执行当前迭代循环节点（带上下文重载，默认 true）
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
        return ComponentSupport.getTag(this);
    }

    /**
     * 将节点配置的 cmpData JSON 反序列化为强类型 Bean 或 Record
     *
     * @param dataClass 目标配置数据 Class
     * @param <D>       配置数据泛型
     * @return 反序列化后的配置对象，若无配置数据则返回 null
     */
    public <D> D getCmpData(Class<D> dataClass) {
        return ComponentSupport.getCmpData(this, dataClass);
    }

    /**
     * 将节点配置的 cmpData JSON 数组反序列化为强类型列表
     *
     * @param elementClass 列表元素 Class
     * @param <D>          元素泛型
     * @return 反序列化后的列表，若无数据返回空列表
     */
    public <D> List<D> getCmpDataList(Class<D> elementClass) {
        return ComponentSupport.getCmpDataList(this, elementClass);
    }
}
