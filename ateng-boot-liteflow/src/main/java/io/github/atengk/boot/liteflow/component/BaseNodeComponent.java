package io.github.atengk.boot.liteflow.component;

import com.yomahub.liteflow.core.NodeComponent;
import com.yomahub.liteflow.flow.LiteflowResponse;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.liteflow.exception.LiteflowExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * LiteFlow 泛型受管普通处理组件基类
 * 自动绑定业务强类型上下文并提供强类型传参辅助（{@link #getTag()}、{@link #getCmpData(Class)}），
 * 支持就地优雅降级（{@link #onError(Exception, Object)}）与 Saga 逆序反向补偿（{@link #rollback(Object)}）防御安全网。
 *
 * @param <T> 业务上下文类型
 * @author Ateng
 * @since 2026-11-03
 */
public abstract class BaseNodeComponent<T> extends NodeComponent {

    private static final Logger log = LoggerFactory.getLogger(BaseNodeComponent.class);

    private final Class<T> contextClass;

    protected BaseNodeComponent() {
        this.contextClass = ComponentContextResolver.resolveGenericContextClass(getClass(), BaseNodeComponent.class);
        // 自动探测子类是否重写了带上下文的 rollback(T) 或无参 rollback()，若重写则自动向 LiteFlow 登记 isRollback = true
        if (hasRollbackOverride()) {
            this.setRollback(true);
        }
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
     * 执行业务节点处理逻辑
     *
     * @param context 强类型业务上下文
     * @throws Exception 业务执行异常
     */
    public abstract void process(T context) throws Exception;

    /**
     * 节点执行异常时的就地优雅降级钩子
     * <p>默认行为：直接重新向上抛出原始异常，以此中断流水线并触发已执行节点的 Saga 逆序回滚。</p>
     * <p>降级范式：子类重写此方法并在内部设置保底数据，<strong>且不向上抛出异常</strong>。
     * 支持就地更新入参 {@code context} 并返回，或返回全新的保底上下文实例（框架将自动同步保底属性至当前执行槽）。
     * 流水线将视为当前节点降级恢复成功，继续推进后续节点的执行。</p>
     *
     * @param e       捕获的业务执行异常
     * @param context 强类型业务上下文
     * @return 降级后的业务上下文（入参自身或全新构造的保底对象）
     * @throws Exception 若无需降级，继续抛出异常以中断流水线
     */
    public T onError(Exception e, T context) throws Exception {
        throw e;
    }

    @Override
    public void process() throws Exception {
        T context = getContext();
        try {
            process(context);
        } catch (Exception e) {
            T fallback = onError(e, context);
            if (fallback != null && fallback != context) {
                BeanUtils.copyProperties(fallback, context);
            }
        }
    }

    /**
     * 是否允许执行当前节点（带上下文重载，默认 true）
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
            // 上下文未就绪或报错时回退父类默认行为
            return super.isAccess();
        }
    }

    /**
     * 逆序反向补偿回滚逻辑（带强类型上下文重载，默认空实现）
     *
     * @param context 强类型业务上下文
     * @throws Exception 回滚执行异常
     */
    public void rollback(T context) throws Exception {
        // 默认空实现，由需要 Saga 补偿的子类按需重写
    }

    @Override
    public void rollback() {
        T context;
        try {
            context = getContext();
        } catch (Throwable e) {
            log.error("[LiteFlow Saga] 节点 [{}] 执行逆序反向补偿时获取上下文失败: {}", getNodeId(), e.getMessage(), e);
            return;
        }

        try {
            rollback(context);
        } catch (Throwable t) {
            // 确定性防御异常捕获与审计告警，坚决杜绝补偿自身抛出次生异常导致事务悬挂
            log.error("[LiteFlow Saga] 节点 [{}] 执行逆序反向补偿异常 (已拦截防次生灾害): {}", getNodeId(), t.getMessage(), t);
            onRollbackError(t, context);
        }
    }

    /**
     * 回滚执行异常时的保底审计钩子（默认空实现，子类可按需覆盖用于记录死信或上报运维报警）
     *
     * @param throwable 回滚抛出的异常
     * @param context   强类型业务上下文
     */
    protected void onRollbackError(Throwable throwable, T context) {
        // 默认空实现，子类可覆盖用于记录死信或上报运维报警
    }

    private boolean hasRollbackOverride() {
        Class<?> current = getClass();
        while (current != null && current != BaseNodeComponent.class && current != NodeComponent.class) {
            for (Method method : current.getDeclaredMethods()) {
                if ("rollback".equals(method.getName())) {
                    return true;
                }
            }
            current = current.getSuperclass();
        }
        return false;
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

    /**
     * 提前正常终止整条流水线流转
     * 区别于抛出异常中断（不会触发前序已执行节点的 Saga 逆序补偿，上下文当前状态作为最终结果返回）
     */
    public void endFlow() {
        this.setIsEnd(true);
        log.debug("LiteFlow 节点 [{}] 触发提前正常终止流程链路 (endFlow)", getNodeId());
    }

    /**
     * 向目标下游节点进行点对点私有数据投递（阅后即焚，不污染全局上下文）
     *
     * @param targetNodeId 目标节点 ID
     * @param data         私有传输数据对象
     */
    public void deliverTo(String targetNodeId, Object data) {
        if (targetNodeId == null || targetNodeId.isBlank()) {
            throw new IllegalArgumentException("targetNodeId 不能为空");
        }
        this.sendPrivateDeliveryData(targetNodeId, data);
    }

    /**
     * 接收上游节点私有投递给当前节点的强类型数据
     *
     * @param dataClass 期望的私有数据 Class 类型
     * @param <D>       数据泛型
     * @return 强类型私有数据，若无投递则返回 null
     * @throws IllegalStateException 若实际投递数据类型不匹配
     */
    public <D> D receiveDelivery(Class<D> dataClass) {
        return ComponentSupport.receiveDelivery(this, dataClass);
    }

    /**
     * 在当前组件内部隐式调用子规则流程链（无额外参数重载）
     * 若子流程链失败，自动转译为受管业务异常抛出
     *
     * @param chainId 子流程链 ID
     */
    public void invokeSubChain(String chainId) {
        invokeSubChain(chainId, null);
    }

    /**
     * 在当前组件内部隐式调用子规则流程链
     * 若子流程链失败，自动转译为受管业务异常抛出
     *
     * @param chainId 子流程链 ID
     * @param param   子流程链专属入参
     */
    public void invokeSubChain(String chainId, Object param) {
        if (chainId == null || chainId.isBlank()) {
            throw new IllegalArgumentException("chainId 不能为空");
        }
        LiteflowResponse response = this.invoke2Resp(chainId, param);
        if (response != null && !response.isSuccess()) {
            Throwable cause = response.getCause();
            String msg = "组件 [" + getNodeId() + "] 隐式调用子流程链 [" + chainId + "] 失败: "
                    + (response.getMessage() != null ? response.getMessage() : (cause != null ? cause.getMessage() : "未知异常"));
            throw new LiteflowExecutionException(chainId, getNodeId(), msg, cause);
        }
    }
}
