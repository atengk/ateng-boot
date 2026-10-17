package io.github.atengk.boot.web.sign.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.DigestUtil;
import io.github.atengk.boot.web.idempotent.store.IdempotentKeyStore;
import io.github.atengk.boot.web.properties.WebProperties;
import io.github.atengk.boot.web.sign.annotation.ApiSign;
import io.github.atengk.boot.web.sign.enums.SignAlgorithm;
import io.github.atengk.boot.web.sign.filter.CachedBodyHttpServletRequest;
import io.github.atengk.boot.web.sign.spi.ApiSigner;
import io.github.atengk.boot.web.sign.spi.SignKeyProvider;
import io.github.atengk.boot.web.sign.util.SignCanonicalUtil;
import io.github.atengk.boot.web.util.ServletUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotationUtils;
import io.github.atengk.boot.core.util.IdUtil;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * API 双向数据加签验签与防重放切面
 * 收敛加签验签逻辑，严格遵循 ADR-0002 显式契约，拒绝使用全局 ResponseBodyAdvice
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Slf4j
@Aspect
public class ApiSignAspect {

    public static final String HEADER_APP_ID = "X-App-Id";
    public static final String HEADER_TIMESTAMP = "X-Timestamp";
    public static final String HEADER_NONCE = "X-Nonce";
    public static final String HEADER_SIGNATURE = "X-Signature";

    private final ApiSigner apiSigner;
    private final SignKeyProvider signKeyProvider;
    private final ObjectProvider<IdempotentKeyStore> idempotentKeyStoreProvider;
    private final ObjectMapper objectMapper;
    private final WebProperties webProperties;

    public ApiSignAspect(ApiSigner apiSigner,
                         SignKeyProvider signKeyProvider,
                         ObjectProvider<IdempotentKeyStore> idempotentKeyStoreProvider,
                         ObjectMapper objectMapper,
                         WebProperties webProperties) {
        this.apiSigner = Objects.requireNonNull(apiSigner, "ApiSigner must not be null");
        this.signKeyProvider = Objects.requireNonNull(signKeyProvider, "SignKeyProvider must not be null");
        this.idempotentKeyStoreProvider = Objects.requireNonNull(idempotentKeyStoreProvider, "IdempotentKeyStoreProvider must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper must not be null");
        this.webProperties = Objects.requireNonNull(webProperties, "WebProperties must not be null");
    }

    @Around("@annotation(apiSign) || @within(apiSign)")
    public Object around(ProceedingJoinPoint joinPoint, ApiSign apiSign) throws Throwable {
        ApiSign effectiveSign = resolveAnnotation(joinPoint, apiSign);
        if (effectiveSign == null) {
            return joinPoint.proceed();
        }

        HttpServletRequest request = ServletUtil.getRequest();
        String appId = resolveAppId(request);

        // 1. 入站请求防篡改验签与防重放校验
        if (effectiveSign.verifyRequest() && request != null) {
            verifyInboundRequest(joinPoint, request, effectiveSign, appId);
        }

        // 2. 执行目标 Controller 业务逻辑（强类型显式返回）
        Object proceedResult = joinPoint.proceed();

        // 3. 出站响应数据加签回写
        if (effectiveSign.signResponse()) {
            signOutboundResponse(request, proceedResult, appId);
        }

        return proceedResult;
    }

    private void verifyInboundRequest(ProceedingJoinPoint joinPoint,
                                      HttpServletRequest request,
                                      ApiSign apiSign,
                                      String appId) {
        String timestampStr = request.getHeader(HEADER_TIMESTAMP);
        String nonce = request.getHeader(HEADER_NONCE);
        String signature = request.getHeader(HEADER_SIGNATURE);

        // (1) 必备安全请求头校验
        if (!StringUtils.hasText(timestampStr) || !StringUtils.hasText(nonce) || !StringUtils.hasText(signature)) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "缺少签名必要请求头 (X-Timestamp, X-Nonce, X-Signature)");
        }

        // (2) 请求时间戳时效性校验
        long clientTime;
        try {
            clientTime = Long.parseLong(timestampStr);
            if (timestampStr.length() <= 10) {
                clientTime *= 1000L;
            }
        } catch (NumberFormatException e) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "非法的时间戳格式: " + timestampStr);
        }

        long now = System.currentTimeMillis();
        long expireMillis = apiSign.expireSeconds() * 1000L;
        if (Math.abs(now - clientTime) > expireMillis) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "请求签名已过期");
        }

        // (3) Nonce 防重放原子排重校验（深度复用 IdempotentKeyStore）
        IdempotentKeyStore keyStore = idempotentKeyStoreProvider.getIfAvailable();
        if (keyStore != null) {
            String nonceKey = "sign:nonce:" + appId + ":" + nonce;
            boolean acquired = keyStore.tryLock(nonceKey, apiSign.expireSeconds(), TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("ApiSign detected repeated nonce [{}] for appId [{}]", nonce, appId);
                throw new BusinessException(CommonResultCode.REPEAT_SUBMIT, "请求已被处理或存在重放风险");
            }
        } else {
            log.warn("ApiSign 未配置 IdempotentKeyStore 实例，已跳过 Nonce 防重放校验: appId=[{}], nonce=[{}]", appId, nonce);
        }

        // (4) 提取并计算请求体摘要
        String bodySha256 = resolveRequestBodySha256(joinPoint, request);

        // (5) 拼装标准化待签串
        String canonicalString = SignCanonicalUtil.buildRequestCanonicalString(
                request.getMethod(),
                request.getRequestURI(),
                timestampStr,
                nonce,
                request.getParameterMap(),
                bodySha256
        );

        // (6) 调用 ApiSigner SPI 执行合法性验证
        String verifyKey = getVerifyKey(appId);
        if (!StringUtils.hasText(verifyKey)) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "未配置对应应用的验签密钥: " + appId);
        }

        boolean valid = apiSigner.verify(canonicalString, signature, verifyKey);
        if (!valid) {
            log.warn("ApiSign verification failed for appId [{}], uri [{}]", appId, request.getRequestURI());
            throw new BusinessException(CommonResultCode.BAD_REQUEST, apiSign.message());
        }
    }

    private void signOutboundResponse(HttpServletRequest request, Object proceedResult, String appId) {
        HttpServletResponse response = ServletUtil.getResponse();
        if (response == null) {
            return;
        }

        try {
            String reqTimestamp = request != null ? request.getHeader(HEADER_TIMESTAMP) : null;
            String reqNonce = request != null ? request.getHeader(HEADER_NONCE) : null;

            String respTimestamp = StringUtils.hasText(reqTimestamp) ? reqTimestamp : String.valueOf(System.currentTimeMillis());
            String respNonce = StringUtils.hasText(reqNonce) ? reqNonce : IdUtil.fastSimpleUUID();

            String responseJson = (proceedResult != null) ? objectMapper.writeValueAsString(proceedResult) : "";
            String responseBodySha256 = DigestUtil.sha256Hex(responseJson);

            String responseCanonical = SignCanonicalUtil.buildResponseCanonicalString(respTimestamp, respNonce, responseBodySha256);
            String signKey = getSignKey(appId);

            if (StringUtils.hasText(signKey)) {
                String responseSignature = apiSigner.sign(responseCanonical, signKey);
                response.setHeader(HEADER_SIGNATURE, responseSignature);
                response.setHeader(HEADER_TIMESTAMP, respTimestamp);
                response.setHeader(HEADER_NONCE, respNonce);
            }
        } catch (Exception e) {
            log.error("ApiSign outbound response signing failed: {}", e.getMessage(), e);
        }
    }

    private String resolveRequestBodySha256(ProceedingJoinPoint joinPoint, HttpServletRequest request) {
        if ("GET".equalsIgnoreCase(request.getMethod()) || "DELETE".equalsIgnoreCase(request.getMethod())
                || "HEAD".equalsIgnoreCase(request.getMethod()) || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return DigestUtil.sha256Hex("");
        }

        if (request instanceof CachedBodyHttpServletRequest cached) {
            byte[] cachedBody = cached.getCachedBody();
            if (cachedBody != null && cachedBody.length > 0) {
                return DigestUtil.sha256Hex(cachedBody);
            }
            return DigestUtil.sha256Hex("");
        }

        // 备用兜底：若未经过 Filter 包装且为写请求，查找带有 @RequestBody 的实参
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        java.lang.annotation.Annotation[][] parameterAnnotations = method.getParameterAnnotations();
        Object[] args = joinPoint.getArgs();
        if (args != null && parameterAnnotations != null) {
            for (int i = 0; i < args.length; i++) {
                if (i < parameterAnnotations.length) {
                    for (java.lang.annotation.Annotation ann : parameterAnnotations[i]) {
                        if (ann instanceof org.springframework.web.bind.annotation.RequestBody) {
                            try {
                                return DigestUtil.sha256Hex(objectMapper.writeValueAsBytes(args[i]));
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
            }
        }

        return DigestUtil.sha256Hex("");
    }

    private String getVerifyKey(String appId) {
        SignAlgorithm algorithm = webProperties.getSign().getAlgorithm();
        return algorithm == SignAlgorithm.RSA ? signKeyProvider.getPublicKey(appId) : signKeyProvider.getSecretKey(appId);
    }

    private String getSignKey(String appId) {
        SignAlgorithm algorithm = webProperties.getSign().getAlgorithm();
        return algorithm == SignAlgorithm.RSA ? signKeyProvider.getPrivateKey(appId) : signKeyProvider.getSecretKey(appId);
    }

    private String resolveAppId(HttpServletRequest request) {
        if (request != null) {
            String appId = request.getHeader(HEADER_APP_ID);
            if (StringUtils.hasText(appId)) {
                return appId;
            }
        }
        return webProperties.getSign().getAppId();
    }

    private ApiSign resolveAnnotation(ProceedingJoinPoint joinPoint, ApiSign apiSign) {
        if (apiSign != null) {
            return apiSign;
        }
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        ApiSign methodAnnotation = AnnotationUtils.findAnnotation(method, ApiSign.class);
        if (methodAnnotation != null) {
            return methodAnnotation;
        }
        return AnnotationUtils.findAnnotation(joinPoint.getTarget().getClass(), ApiSign.class);
    }
}
