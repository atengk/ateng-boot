package io.github.atengk.boot.liteflow.component;

import com.yomahub.liteflow.core.NodeComponent;
import io.github.atengk.boot.core.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
     * 执行业务节点处理逻辑
     *
     * @param context 强类型业务上下文
     * @throws Exception 业务执行异常
     */
    public abstract void process(T context) throws Exception;

    /**
     * 节点执行异常时的就地优雅降级钩子
     * <p>默认行为：直接重新向上抛出原始异常，以此中断流水线并触发已执行节点的 Saga 逆序回滚。</p>
     * <p>降级范式：子类重写此方法并在内部就地设置保底数据或标记降级，<strong>且不向上抛出异常</strong>。
     * 流水线将视为当前节点降级恢复成功，继续推进后续节点的执行。</p>
     *
     * @param e       捕获的业务执行异常
     * @param context 强类型业务上下文
     * @return 降级后的业务上下文
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
            onError(e, context);
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
