package io.github.atengk.boot.milvus.exception;

/**
 * Milvus 底层驱动异常转译器
 *
 * @author Ateng
 * @since 2026-11-04
 */
public final class MilvusExceptionTranslator {

    private MilvusExceptionTranslator() {
    }

    /**
     * 将底层捕获的异常转译为语义明确的 MilvusException，100% 保留原始 cause 链
     *
     * @param throwable 原始异常
     * @return 转译后的 MilvusException
     */
    public static MilvusException translate(Throwable throwable) {
        return translate("执行操作", throwable);
    }

    /**
     * 将底层捕获的异常转译为语义明确的 MilvusException，并附加具体操作描述
     *
     * @param operation 具体操作描述
     * @param throwable 原始异常
     * @return 转译后的 MilvusException
     */
    public static MilvusException translate(String operation, Throwable throwable) {
        if (throwable instanceof MilvusException milvusException) {
            return milvusException;
        }

        String message = throwable.getMessage() != null ? throwable.getMessage() : throwable.toString();
        String lowerMsg = message.toLowerCase();

        if (lowerMsg.contains("collection not found") || lowerMsg.contains("can't find collection")
                || lowerMsg.contains("collection does not exist")) {
            return new MilvusException(MilvusResultCode.COLLECTION_NOT_FOUND,
                    operation + "失败: 集合不存在 [" + message + "]", throwable);
        }

        if (lowerMsg.contains("already exist") || lowerMsg.contains("collection exist")) {
            return new MilvusException(MilvusResultCode.COLLECTION_ALREADY_EXISTS,
                    operation + "失败: 集合已存在 [" + message + "]", throwable);
        }

        if (lowerMsg.contains("not loaded") || lowerMsg.contains("has not been loaded")) {
            return new MilvusException(MilvusResultCode.COLLECTION_NOT_LOADED,
                    operation + "失败: 集合未加载至内存 [" + message + "]", throwable);
        }

        if (lowerMsg.contains("partition not found") || lowerMsg.contains("partition does not exist")) {
            return new MilvusException(MilvusResultCode.PARTITION_NOT_FOUND,
                    operation + "失败: 分区不存在 [" + message + "]", throwable);
        }

        if (lowerMsg.contains("dimension mismatch") || lowerMsg.contains("vector dimension")) {
            return new MilvusException(MilvusResultCode.VECTOR_DIMENSION_MISMATCH,
                    operation + "失败: 向量维度不匹配 [" + message + "]", throwable);
        }

        return new MilvusException(MilvusResultCode.OPERATION_FAILED,
                operation + "失败: " + message, throwable);
    }
}
