package io.github.atengk.boot.mybatisplus.dynamicquery;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 方法引用元数据推导工具 DynamicLambdaHelper 单元测试
 *
 * @author Ateng
 * @since 2026-10-19
 */
class DynamicLambdaHelperTest {

    interface FakeUserMapper {
        IPage<String> selectUserDeptPage(IPage<String> page, Wrapper<String> wrapper);
        List<String> selectUserList(Wrapper<String> wrapper);
    }

    static class FakeUserMapperImpl implements FakeUserMapper, Serializable {
        @Override
        public IPage<String> selectUserDeptPage(IPage<String> page, Wrapper<String> wrapper) {
            return null;
        }

        @Override
        public List<String> selectUserList(Wrapper<String> wrapper) {
            return null;
        }
    }

    @Test
    @DisplayName("测试从 DynamicMapperPageMethod 方法引用中精确提取目标方法名称")
    void testResolvePageMethodName() {
        FakeUserMapper mapper = new FakeUserMapperImpl();
        DynamicMapperPageMethod<String> methodRef = mapper::selectUserDeptPage;

        String methodName = DynamicLambdaHelper.resolveMethodName(methodRef);
        assertEquals("selectUserDeptPage", methodName);
    }

    @Test
    @DisplayName("测试从 DynamicMapperListMethod 方法引用中精确提取目标方法名称")
    void testResolveListMethodName() {
        FakeUserMapper mapper = new FakeUserMapperImpl();
        DynamicMapperListMethod<String> methodRef = mapper::selectUserList;

        String methodName = DynamicLambdaHelper.resolveMethodName(methodRef);
        assertEquals("selectUserList", methodName);
    }

    @Test
    @DisplayName("测试传入 null 或非 SerializedLambda 实例时的空安全回退")
    void testDefensiveNullSafety() {
        assertNull(DynamicLambdaHelper.resolveMethodName(null));
        // 普通 lambda 表达式（未捕获可识别方法）
        DynamicMapperPageMethod<String> customLambda = (page, wrapper) -> null;
        // 普通无关联方法的 lambda 解析不会崩溃，返回 null 或 lambda 方法名
        DynamicLambdaHelper.resolveMethodName(customLambda);
    }
}
