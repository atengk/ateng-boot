package io.github.atengk.boot.ai.graph.parallel;

import io.github.atengk.boot.ai.graph.BaseGraphState;

import java.time.Duration;
import java.util.Objects;

/**
 * 并行扇出认知分支契约模型
 * <p>
 * 封装分支唯一标识、业务描述、异步任务处理器及单分支硬超时阈值（{@link Duration}）。
 * </p>
 *
 * @param <S> 强类型状态模型，继承自 {@link BaseGraphState}
 * @author Ateng
 * @since 2026-11-22
 */
public class ParallelBranch<S extends BaseGraphState> {

    private final String name;
    private final String description;
    private final BranchHandler<S> handler;
    private final Duration timeout;

    public ParallelBranch(String name, String description, BranchHandler<S> handler, Duration timeout) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ParallelBranch name must not be null or blank");
        }
        this.name = name.trim();
        this.description = description != null ? description.trim() : "";
        this.handler = Objects.requireNonNull(handler, "ParallelBranch handler must not be null");
        if (timeout != null && (timeout.isNegative() || timeout.isZero())) {
            throw new IllegalArgumentException("ParallelBranch timeout must be positive when specified");
        }
        this.timeout = timeout;
    }

    public static <S extends BaseGraphState> ParallelBranch<S> of(String name, BranchHandler<S> handler) {
        return new ParallelBranch<>(name, "", handler, null);
    }

    public static <S extends BaseGraphState> ParallelBranch<S> of(String name, String description, BranchHandler<S> handler) {
        return new ParallelBranch<>(name, description, handler, null);
    }

    public static <S extends BaseGraphState> ParallelBranch<S> of(String name, Duration timeout, BranchHandler<S> handler) {
        return new ParallelBranch<>(name, "", handler, timeout);
    }

    public static <S extends BaseGraphState> Builder<S> builder() {
        return new Builder<>();
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public BranchHandler<S> getHandler() {
        return handler;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public static final class Builder<S extends BaseGraphState> {

        private String name;
        private String description = "";
        private BranchHandler<S> handler;
        private Duration timeout;

        private Builder() {
        }

        public Builder<S> name(String name) {
            this.name = name;
            return this;
        }

        public Builder<S> description(String description) {
            this.description = description;
            return this;
        }

        public Builder<S> handler(BranchHandler<S> handler) {
            this.handler = handler;
            return this;
        }

        public Builder<S> timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public ParallelBranch<S> build() {
            return new ParallelBranch<>(name, description, handler, timeout);
        }
    }
}
