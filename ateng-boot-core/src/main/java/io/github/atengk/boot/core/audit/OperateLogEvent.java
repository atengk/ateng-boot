package io.github.atengk.boot.core.audit;

import org.springframework.context.ApplicationEvent;

/**
 * 结构化操作审计事件
 * 当业务操作完成并组装 {@link OperateLogModel} 之后发布，支持业务系统通过 @EventListener 异步消费审计数据
 *
 * @author Ateng
 * @since 2026-10-18
 */
public class OperateLogEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    private final OperateLogModel logModel;

    public OperateLogEvent(Object source, OperateLogModel logModel) {
        super(source);
        this.logModel = logModel;
    }

    /**
     * 获取审计元数据模型载荷
     *
     * @return 结构化审计模型
     */
    public OperateLogModel getLogModel() {
        return logModel;
    }
}
