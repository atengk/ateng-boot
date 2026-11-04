package io.github.atengk.boot.milvus;

import io.github.atengk.boot.milvus.exception.MilvusException;
import io.github.atengk.boot.milvus.exception.MilvusExceptionTranslator;
import io.github.atengk.boot.milvus.exception.MilvusResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milvus 异常与错误码单元测试
 *
 * @author Ateng
 * @since 2026-11-04
 */
class MilvusResultCodeAndExceptionTest {

    @Test
    @DisplayName("测试状态码枚举契约与异常因果链传递")
    void testExceptionCreationAndCauseChain() {
        RuntimeException originalCause = new RuntimeException("底层网络中断");
        MilvusException exception = new MilvusException(MilvusResultCode.CLIENT_INIT_FAILED, "初始化超时", originalCause);

        assertThat(exception.getResultCode()).isEqualTo(MilvusResultCode.CLIENT_INIT_FAILED);
        assertThat(exception.getCode()).isEqualTo(50010);
        assertThat(exception.getMsg()).isEqualTo("初始化超时");
        assertThat(exception.getCause()).isSameAs(originalCause);
    }

    @Test
    @DisplayName("测试异常转译器对各种原生关键字的精准分类与因果保留")
    void testExceptionTranslator() {
        // 1. 集合不存在
        RuntimeException ex1 = new RuntimeException("collection not found: knowledge_base");
        MilvusException res1 = MilvusExceptionTranslator.translate("查询向量", ex1);
        assertThat(res1.getResultCode()).isEqualTo(MilvusResultCode.COLLECTION_NOT_FOUND);
        assertThat(res1.getCause()).isSameAs(ex1);

        // 2. 集合已存在
        RuntimeException ex2 = new RuntimeException("collection already exists: demo_collection");
        MilvusException res2 = MilvusExceptionTranslator.translate("创建集合", ex2);
        assertThat(res2.getResultCode()).isEqualTo(MilvusResultCode.COLLECTION_ALREADY_EXISTS);
        assertThat(res2.getCause()).isSameAs(ex2);

        // 3. 集合未加载至内存
        RuntimeException ex3 = new RuntimeException("collection has not been loaded");
        MilvusException res3 = MilvusExceptionTranslator.translate("执行检索", ex3);
        assertThat(res3.getResultCode()).isEqualTo(MilvusResultCode.COLLECTION_NOT_LOADED);
        assertThat(res3.getCause()).isSameAs(ex3);

        // 4. 分区不存在
        RuntimeException ex4 = new RuntimeException("partition not found: p_202611");
        MilvusException res4 = MilvusExceptionTranslator.translate("分区写入", ex4);
        assertThat(res4.getResultCode()).isEqualTo(MilvusResultCode.PARTITION_NOT_FOUND);
        assertThat(res4.getCause()).isSameAs(ex4);

        // 5. 向量维度不匹配
        RuntimeException ex5 = new RuntimeException("vector dimension mismatch: expected 1536 but got 768");
        MilvusException res5 = MilvusExceptionTranslator.translate("向量存储", ex5);
        assertThat(res5.getResultCode()).isEqualTo(MilvusResultCode.VECTOR_DIMENSION_MISMATCH);
        assertThat(res5.getCause()).isSameAs(ex5);

        // 6. 普通操作失败
        RuntimeException ex6 = new RuntimeException("gRPC timeout");
        MilvusException res6 = MilvusExceptionTranslator.translate("批量操作", ex6);
        assertThat(res6.getResultCode()).isEqualTo(MilvusResultCode.OPERATION_FAILED);
        assertThat(res6.getCause()).isSameAs(ex6);

        // 7. 若已经是 MilvusException，直接返回原实例
        MilvusException direct = new MilvusException(MilvusResultCode.TENANT_ID_REQUIRED);
        assertThat(MilvusExceptionTranslator.translate(direct)).isSameAs(direct);
    }
}
