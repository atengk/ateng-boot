package io.github.atengk.boot.sample.core.context;

import io.github.atengk.boot.core.context.AsyncContextSnapshotProvider;
import org.springframework.stereotype.Component;

/**
 * 样例业务自定义上下文快照提供者
 * 实现 AsyncContextSnapshotProvider SPI 接口，实现扩展维度在受管线程池中的无缝透传与清理
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Component
public class SampleCustomContextSnapshotProvider implements AsyncContextSnapshotProvider {

    @Override
    public Object capture() {
        return SampleCustomContextHolder.get();
    }

    @Override
    public void apply(Object snapshot) {
        if (snapshot instanceof String data) {
            SampleCustomContextHolder.set(data);
        } else {
            SampleCustomContextHolder.clear();
        }
    }

    @Override
    public void restore(Object previous) {
        if (previous instanceof String data) {
            SampleCustomContextHolder.set(data);
        } else {
            SampleCustomContextHolder.clear();
        }
    }
}
