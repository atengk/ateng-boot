package io.github.atengk.boot.job.xxl.context;

import com.xxl.job.core.context.XxlJobContext;
import io.github.atengk.boot.core.context.AsyncContextSnapshotProvider;

/**
 * XXL-Job 调度上下文异步全链路传播提供者
 *
 * <p>实现 {@link AsyncContextSnapshotProvider} SPI 契约，治理 XXL-Job 官方底层 {@link InheritableThreadLocal}
 * 在受管异步线程池复用时的浅拷贝污染风险。在异步任务派发前抓取父线程的 {@link XxlJobContext}，
 * 在工作线程执行前安全注入，并在执行完毕后无条件强制还原工作线程的原生现场，彻底防止线程池脏读。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class XxlJobContextSnapshotProvider implements AsyncContextSnapshotProvider {

    @Override
    public Object capture() {
        return XxlJobContext.getXxlJobContext();
    }

    @Override
    public Object capturePrevious() {
        return XxlJobContext.getXxlJobContext();
    }

    @Override
    public void apply(Object snapshot) {
        if (snapshot instanceof XxlJobContext context) {
            XxlJobContext.setXxlJobContext(context);
        } else {
            XxlJobContext.setXxlJobContext(null);
        }
    }

    @Override
    public void restore(Object previous) {
        if (previous instanceof XxlJobContext context) {
            XxlJobContext.setXxlJobContext(context);
        } else {
            XxlJobContext.setXxlJobContext(null);
        }
    }

}
