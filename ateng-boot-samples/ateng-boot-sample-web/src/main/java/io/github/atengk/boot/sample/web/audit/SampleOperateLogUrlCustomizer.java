package io.github.atengk.boot.sample.web.audit;

import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.web.audit.OperateLogUrlContext;
import io.github.atengk.boot.web.audit.OperateLogUrlCustomizer;
import org.springframework.stereotype.Component;

/**
 * 示例操作审计 URL 自定义脱敏扩展点实现
 * 演示在保留常规接口原始 URI 的同时，对特定敏感令牌吊销路径的尾段句柄执行局部保留首尾掩码
 *
 * @author Ateng
 * @since 2026-11-26
 */
@Component
public class SampleOperateLogUrlCustomizer implements OperateLogUrlCustomizer {

    private static final String TOKEN_PATH_PREFIX = "/sample/web/observability/audit/tokens/";

    @Override
    public String customize(OperateLogUrlContext context) {
        if (context == null) {
            return null;
        }
        String rawUri = context.rawUri();
        if (rawUri == null || !rawUri.startsWith(TOKEN_PATH_PREFIX)) {
            return context.resolvedUrl();
        }
        String rawToken = rawUri.substring(TOKEN_PATH_PREFIX.length());
        if (rawToken.isEmpty()) {
            return context.resolvedUrl();
        }
        return TOKEN_PATH_PREFIX + DesensitizeUtil.maskKeepHeadTail(rawToken, 4, 2);
    }
}
