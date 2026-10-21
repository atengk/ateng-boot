package io.github.atengk.boot.sample.http.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 微信支付 Native 下单响应传输对象
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WechatNativeOrderResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    @JsonProperty("code_url")
    private String codeUrl;
}
