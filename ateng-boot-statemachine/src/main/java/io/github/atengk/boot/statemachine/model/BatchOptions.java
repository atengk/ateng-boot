package io.github.atengk.boot.statemachine.model;

/**
 * 大批量切片状态流转配置选项
 *
 * @author Ateng
 * @since 2026-11-01
 */
public class BatchOptions {

    public static final int DEFAULT_BATCH_SIZE = 200;

    private final int batchSize;
    private final boolean failFast;

    public BatchOptions(int batchSize, boolean failFast) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be greater than 0");
        }
        this.batchSize = batchSize;
        this.failFast = failFast;
    }

    public static BatchOptions defaults() {
        return new BatchOptions(DEFAULT_BATCH_SIZE, false);
    }

    public static BatchOptions of(int batchSize) {
        return new BatchOptions(batchSize, false);
    }

    public static BatchOptions of(int batchSize, boolean failFast) {
        return new BatchOptions(batchSize, failFast);
    }

    public static BatchOptions failFast() {
        return new BatchOptions(DEFAULT_BATCH_SIZE, true);
    }

    public static BatchOptions failFast(int batchSize) {
        return new BatchOptions(batchSize, true);
    }

    public static Builder builder() {
        return new Builder();
    }

    public int getBatchSize() {
        return batchSize;
    }

    public boolean isFailFast() {
        return failFast;
    }

    public boolean isContinueOnError() {
        return !failFast;
    }

    @Override
    public String toString() {
        return "BatchOptions{" +
                "batchSize=" + batchSize +
                ", failFast=" + failFast +
                '}';
    }

    public static class Builder {
        private int batchSize = DEFAULT_BATCH_SIZE;
        private boolean failFast = false;

        public Builder batchSize(int batchSize) {
            if (batchSize <= 0) {
                throw new IllegalArgumentException("batchSize must be greater than 0");
            }
            this.batchSize = batchSize;
            return this;
        }

        public Builder failFast(boolean failFast) {
            this.failFast = failFast;
            return this;
        }

        public Builder continueOnError(boolean continueOnError) {
            this.failFast = !continueOnError;
            return this;
        }

        public BatchOptions build() {
            return new BatchOptions(batchSize, failFast);
        }
    }
}
