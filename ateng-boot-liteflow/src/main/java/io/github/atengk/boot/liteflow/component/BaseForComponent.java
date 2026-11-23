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
        return ComponentSupport.resolveContext(this, this.contextClass);
    }

    /**
     * 执行 FOR 循环次数决策
     *
     * @param context 强类型业务上下文
     * @return 目标循环执行次数
     * @throws Exception 业务执行异常
     */
    public abstract int processFor(T context) throws Exception;

    /**
     * 循环计数节点执行异常时的就地优雅降级钩子
     * 默认行为：直接向上抛出原始异常，中断流水线。
     * 降级范式：子类重写此方法并返回保底循环次数（如 0），流水线将视为降级成功。
     *
     * @param e       捕获的业务执行异常
     * @param context 强类型业务上下文
     * @return 降级后的目标循环次数
     * @throws Exception 若无需降级，继续抛出异常
     */
    public int onError(Exception e, T context) throws Exception {
        throw e;
    }

    @Override
    public int processFor() throws Exception {
        T context = getContext();
        try {
            return processFor(context);
        } catch (Exception e) {
            return onError(e, context);
        }
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

    /**
     * 获取流程初始强类型请求入参
     *
     * @param paramClass 期望的入参 Class 类型
     * @param <P>        入参泛型
     * @return 强类型入参，若未传递则返回 null
     * @throws IllegalStateException 若实际入参类型与期望类型不匹配
     */
    public <P> P getRequestData(Class<P> paramClass) {
        return ComponentSupport.getRequestData(this, paramClass);
    }

    /**
     * 获取当前循环组件的迭代轮次下标 (从 0 开始)
     *
     * @return 循环下标，若未在循环上下文执行则返回 null
     */
    @Override
    public Integer getLoopIndex() {
        return ComponentSupport.getLoopIndex(this);
    }

    /**
     * 获取当前循环组件的强类型迭代对象
     *
     * @param elementClass 期望的迭代元素 Class 类型
     * @param <E>          元素泛型
     * @return 强类型当前迭代元素，若无迭代对象则返回 null
     * @throws IllegalStateException 若当前迭代对象类型与期望类型不匹配
     */
    public <E> E getCurrLoopObject(Class<E> elementClass) {
        return ComponentSupport.getCurrLoopObject(this, elementClass);
    }
}
