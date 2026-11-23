package io.github.atengk.boot.openapi.spi;

import io.github.atengk.boot.core.code.IResultCode;
import org.springframework.core.Ordered;

import java.util.Collection;

/**
 * 业务状态码字典扩展点 SPI 接口
 * <p>
 * 允许垂直业务 Starter 或业务子系统向全局 OpenAPI 文档动态注入领域专属业务状态码字典。
 * 容器内所有实现该接口的 Bean 将被 {@link io.github.atengk.boot.openapi.customizer.ResultCodeDictionaryCustomizer}
 * 自动收集，并动态渲染至 OpenAPI 契约的 {@code Info.description} 中。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
public interface ResultCodeProvider extends Ordered {

    /**
     * 获取状态码字典分类标题或领域名称
     * <p>例如："### 订单中心业务响应状态码字典 (Order Result Codes)"</p>
     * 若返回 null 或空字符串，则使用默认标题。
     *
     * @return 字典分类标题
     */
    default String getTitle() {
        return null;
    }

    /**
     * 获取当前 Provider 提供的业务状态码集合
     *
     * @return 业务状态码集合（若无状态码则返回空集合，严禁返回 null）
     */
    Collection<? extends IResultCode> getResultCodes();

    /**
     * 解析指定状态码的详细业务场景说明（可选扩展点）
     *
     * @param resultCode 状态码契约对象
     * @return 场景说明；若返回 null 或空字符串，则默认使用 {@link IResultCode#getMsg()}
     */
    default String resolveScenario(IResultCode resultCode) {
        return null;
    }

    /**
     * 排序权重，默认优先级为 0（数值越小排序越靠前）
     *
     * @return 排序序号
     */
    @Override
    default int getOrder() {
        return 0;
    }
}
