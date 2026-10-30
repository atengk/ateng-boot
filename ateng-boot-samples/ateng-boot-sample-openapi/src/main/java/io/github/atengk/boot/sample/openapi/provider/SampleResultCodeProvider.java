package io.github.atengk.boot.sample.openapi.provider;

import io.github.atengk.boot.core.code.IResultCode;
import io.github.atengk.boot.openapi.spi.ResultCodeProvider;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collection;

/**
 * 样例工程业务状态码字典 SPI 演示组件
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Component
public class SampleResultCodeProvider implements ResultCodeProvider {

    /**
     * 业务状态码枚举
     */
    public enum SampleBusinessCode implements IResultCode {
        ARTICLE_AUDIT_PENDING(8001, "文章正在审核中"),
        USER_ACCOUNT_LOCKED(8002, "用户账号已被风控锁定");

        private final Integer code;
        private final String msg;

        SampleBusinessCode(Integer code, String msg) {
            this.code = code;
            this.msg = msg;
        }

        @Override
        public Integer getCode() {
            return code;
        }

        @Override
        public String getMsg() {
            return msg;
        }
    }

    @Override
    public String getTitle() {
        return "### 样例工程自定义业务状态码字典 (Sample Result Codes)";
    }

    @Override
    public Collection<? extends IResultCode> getResultCodes() {
        return Arrays.asList(SampleBusinessCode.values());
    }

    @Override
    public String resolveScenario(IResultCode resultCode) {
        if (resultCode == SampleBusinessCode.ARTICLE_AUDIT_PENDING) {
            return "敏感词拦截或新发布内容进入人工审核队列";
        }
        if (resultCode == SampleBusinessCode.USER_ACCOUNT_LOCKED) {
            return "密码输错超限或异常地理位置登录触发风控冻结";
        }
        return resultCode.getMsg();
    }

    @Override
    public int getOrder() {
        return 10;
    }
}
