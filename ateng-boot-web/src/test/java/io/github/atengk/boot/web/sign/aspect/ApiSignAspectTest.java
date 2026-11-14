package io.github.atengk.boot.web.sign.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.DigestUtil;
import io.github.atengk.boot.web.idempotent.store.IdempotentKeyStore;
import io.github.atengk.boot.web.idempotent.store.InMemoryIdempotentKeyStore;
import io.github.atengk.boot.web.properties.WebProperties;
import io.github.atengk.boot.web.sign.annotation.ApiSign;
import io.github.atengk.boot.web.sign.enums.SignAlgorithm;
import io.github.atengk.boot.web.sign.provider.DefaultSignKeyProvider;
import io.github.atengk.boot.web.sign.signer.HmacSha256Signer;
import io.github.atengk.boot.web.sign.signer.RsaSigner;
import io.github.atengk.boot.web.sign.signer.Sm2Signer;
import io.github.atengk.boot.web.sign.spi.ApiSigner;
import io.github.atengk.boot.web.sign.util.SignCanonicalUtil;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * API 双向加签验签切面核心单元测试
 *
 * @author Ateng
 * @since 2026-11-14
 */
@DisplayName("API 双向加签验签切面核心单元测试")
class ApiSignAspectTest {

    private static final String HMAC_SECRET = "TestSecretKey123";
    private static final Sm2Signer.Sm2KeyPair SM2_KEY_PAIR = Sm2Signer.generateKeyPair();
    private static final String RSA_PRIVATE_KEY_PEM;
    private static final String RSA_PUBLIC_KEY_PEM;

    static {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            RSA_PRIVATE_KEY_PEM = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
            RSA_PUBLIC_KEY_PEM = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("初始化测试 RSA 密钥失败", e);
        }
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HmacSha256Signer hmacSigner = new HmacSha256Signer();
    private final RsaSigner rsaSigner = new RsaSigner();
    private final Sm2Signer sm2Signer = new Sm2Signer();

    private WebProperties webProperties;
    private InMemoryIdempotentKeyStore idempotentKeyStore;
    private StaticListableBeanFactory beanFactory;
    private ApiSignAspect aspect;

    @BeforeEach
    void setUp() {
        webProperties = new WebProperties();
        webProperties.getSign().setEnabled(true);
        webProperties.getSign().setAlgorithm(SignAlgorithm.HMAC_SHA256);
        webProperties.getSign().setSecretKey(HMAC_SECRET);
        webProperties.getSign().setRsaPublicKey(RSA_PUBLIC_KEY_PEM);
        webProperties.getSign().setRsaPrivateKey(RSA_PRIVATE_KEY_PEM);
        webProperties.getSign().setSm2PublicKey(SM2_KEY_PAIR.publicKeyHex());
        webProperties.getSign().setSm2PrivateKey(SM2_KEY_PAIR.privateKeyHex());

        idempotentKeyStore = new InMemoryIdempotentKeyStore();
        beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("idempotentKeyStore", idempotentKeyStore);

        aspect = new ApiSignAspect(
                hmacSigner,
                new DefaultSignKeyProvider(webProperties.getSign()),
                beanFactory.getBeanProvider(IdempotentKeyStore.class),
                objectMapper,
                webProperties
        );
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        idempotentKeyStore.destroy();
    }

    @Test
    @DisplayName("同一切面实例支持 @ApiSign 注解按端点动态路由 HMAC_SHA256、RSA 与 SM2 算法")
    void shouldRouteMultipleAlgorithmsWithinSingleAspectInstance() throws Throwable {
        // 1. 默认注解走全局 HMAC_SHA256
        assertSignedInvocation("hmacEndpoint", "/api/orders/hmac", hmacSigner, HMAC_SECRET, HMAC_SECRET);

        // 2. 注解显式覆盖为 RSA
        assertSignedInvocation("rsaEndpoint", "/api/orders/rsa", rsaSigner, RSA_PRIVATE_KEY_PEM, RSA_PUBLIC_KEY_PEM);

        // 3. 注解显式覆盖为 SM2
        assertSignedInvocation("sm2Endpoint", "/api/orders/sm2", sm2Signer, SM2_KEY_PAIR.privateKeyHex(), SM2_KEY_PAIR.publicKeyHex());
    }

    private void assertSignedInvocation(String methodName,
                                        String uri,
                                        ApiSigner clientSigner,
                                        String clientSignKey,
                                        String clientVerifyKey) throws Throwable {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String canonical = SignCanonicalUtil.buildRequestCanonicalString(
                "GET", uri, timestamp, nonce, Map.of(), DigestUtil.sha256Hex("")
        );
        String clientSignature = clientSigner.sign(canonical, clientSignKey);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.addHeader(ApiSignAspect.HEADER_TIMESTAMP, timestamp);
        request.addHeader(ApiSignAspect.HEADER_NONCE, nonce);
        request.addHeader(ApiSignAspect.HEADER_SIGNATURE, clientSignature);
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));

        Method method = SampleSignEndpoints.class.getMethod(methodName);
        ApiSign apiSign = method.getAnnotation(ApiSign.class);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Result<String> expectedBody = Result.success("ok-" + methodName);

        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.getTarget()).thenReturn(new SampleSignEndpoints());
        when(joinPoint.proceed()).thenReturn(expectedBody);

        Object result = aspect.around(joinPoint, apiSign);
        assertThat(result).isEqualTo(expectedBody);

        String respSignature = response.getHeader(ApiSignAspect.HEADER_SIGNATURE);
        assertThat(respSignature).isNotBlank();
        String respCanonical = SignCanonicalUtil.buildResponseCanonicalString(
                response.getHeader(ApiSignAspect.HEADER_TIMESTAMP),
                response.getHeader(ApiSignAspect.HEADER_NONCE),
                DigestUtil.sha256Hex(objectMapper.writeValueAsString(expectedBody).getBytes(StandardCharsets.UTF_8))
        );
        assertThat(clientSigner.verify(respCanonical, respSignature, clientVerifyKey)).isTrue();
    }

    @Test
    @DisplayName("未指定注解算法时优先尊重自定义 ApiSigner，显式指定 SM2 时路由内置 SM2 签名器")
    void shouldPrioritizeCustomApiSignerWhenAnnotationUsesDefault() throws Throwable {
        ApiSigner customSigner = new ApiSigner() {
            @Override
            public String sign(String data, String privateOrSecretKey) {
                return "custom-sig:" + privateOrSecretKey;
            }

            @Override
            public boolean verify(String data, String signature, String publicOrSecretKey) {
                return ("custom-sig:" + publicOrSecretKey).equals(signature);
            }
        };
        ApiSignAspect customAspect = new ApiSignAspect(
                customSigner,
                new DefaultSignKeyProvider(webProperties.getSign()),
                beanFactory.getBeanProvider(IdempotentKeyStore.class),
                objectMapper,
                webProperties
        );

        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders/hmac");
        request.addHeader(ApiSignAspect.HEADER_TIMESTAMP, timestamp);
        request.addHeader(ApiSignAspect.HEADER_NONCE, nonce);
        request.addHeader(ApiSignAspect.HEADER_SIGNATURE, "custom-sig:" + HMAC_SECRET);
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));

        Method hmacMethod = SampleSignEndpoints.class.getMethod("hmacEndpoint");
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(hmacMethod);
        when(joinPoint.getTarget()).thenReturn(new SampleSignEndpoints());
        when(joinPoint.proceed()).thenReturn(Result.success("ok"));

        customAspect.around(joinPoint, hmacMethod.getAnnotation(ApiSign.class));
        assertThat(response.getHeader(ApiSignAspect.HEADER_SIGNATURE)).isEqualTo("custom-sig:" + HMAC_SECRET);

        // 同一实例下，显式指定 @ApiSign(algorithm = SM2) 应旁路自定义 ApiSigner 并路由至内置 Sm2Signer
        String sm2Ts = String.valueOf(System.currentTimeMillis());
        String sm2Nonce = UUID.randomUUID().toString().replace("-", "");
        Method sm2Method = SampleSignEndpoints.class.getMethod("sm2Endpoint");
        ApiSign sm2Ann = sm2Method.getAnnotation(ApiSign.class);
        String sm2BodySha256 = DigestUtil.sha256Hex("");
        String sm2ReqCanonical = SignCanonicalUtil.buildRequestCanonicalString(
                "GET", "/api/orders/sm2", sm2Ts, sm2Nonce, Map.of(), sm2BodySha256);
        String sm2ReqSig = new Sm2Signer().sign(sm2ReqCanonical, SM2_KEY_PAIR.privateKeyHex());

        MockHttpServletRequest sm2Req = new MockHttpServletRequest("GET", "/api/orders/sm2");
        sm2Req.addHeader(ApiSignAspect.HEADER_TIMESTAMP, sm2Ts);
        sm2Req.addHeader(ApiSignAspect.HEADER_NONCE, sm2Nonce);
        sm2Req.addHeader(ApiSignAspect.HEADER_SIGNATURE, sm2ReqSig);
        MockHttpServletResponse sm2Resp = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(sm2Req, sm2Resp));

        ProceedingJoinPoint sm2JoinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature sm2SigMeta = mock(MethodSignature.class);
        Result<String> sm2Body = Result.success("ok-sm2");
        when(sm2JoinPoint.getSignature()).thenReturn(sm2SigMeta);
        when(sm2SigMeta.getMethod()).thenReturn(sm2Method);
        when(sm2JoinPoint.getTarget()).thenReturn(new SampleSignEndpoints());
        when(sm2JoinPoint.proceed()).thenReturn(sm2Body);

        customAspect.around(sm2JoinPoint, sm2Ann);
        String sm2RespSign = sm2Resp.getHeader(ApiSignAspect.HEADER_SIGNATURE);
        assertThat(sm2RespSign).isNotBlank().doesNotStartWith("custom-sig:");
        String sm2RespCanonical = SignCanonicalUtil.buildResponseCanonicalString(
                sm2Resp.getHeader(ApiSignAspect.HEADER_TIMESTAMP),
                sm2Resp.getHeader(ApiSignAspect.HEADER_NONCE),
                DigestUtil.sha256Hex(objectMapper.writeValueAsString(sm2Body).getBytes(StandardCharsets.UTF_8))
        );
        assertThat(new Sm2Signer().verify(sm2RespCanonical, sm2RespSign, SM2_KEY_PAIR.publicKeyHex())).isTrue();
    }

    @Test
    @DisplayName("出站加签失败或缺少加签密钥时 Fail-Closed 抛出保留因果链的 BusinessException")
    void shouldThrowBusinessExceptionWithCauseWhenOutboundSigningFails() throws Throwable {
        // 1. 私钥格式非法导致 RSA 加签异常：必须抛出 BusinessException 且保留原始 cause
        webProperties.getSign().setRsaPrivateKey("INVALID_BASE64_PRIVATE_KEY");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders/rsa-outbound");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));

        Method outboundMethod = SampleSignEndpoints.class.getMethod("rsaOutboundOnlyEndpoint");
        ApiSign apiSign = outboundMethod.getAnnotation(ApiSign.class);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(outboundMethod);
        when(joinPoint.getTarget()).thenReturn(new SampleSignEndpoints());
        when(joinPoint.proceed()).thenReturn(Result.success("sensitive-data"));

        assertThatThrownBy(() -> aspect.around(joinPoint, apiSign))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(CommonResultCode.INTERNAL_SERVER_ERROR.getCode());
                    assertThat(be.getCause()).isNotNull();
                });

        // 2. 未配置加签私钥：Fail-Closed 阻断，严禁返回未加签响应
        webProperties.getSign().setRsaPrivateKey(null);
        webProperties.getSign().setPrivateKey(null);
        assertThatThrownBy(() -> aspect.around(joinPoint, apiSign))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(CommonResultCode.INTERNAL_SERVER_ERROR.getCode()));
    }

    @Test
    @DisplayName("支持自定义 noncePrefix 隔离防重放键空间，默认使用 ateng:sign:nonce:")
    void shouldUseConfiguredNoncePrefix() throws Throwable {
        IdempotentKeyStore mockKeyStore = mock(IdempotentKeyStore.class);
        when(mockKeyStore.tryLock(anyString(), anyLong(), any())).thenReturn(true);

        StaticListableBeanFactory customFactory = new StaticListableBeanFactory();
        customFactory.addBean("idempotentKeyStore", mockKeyStore);

        webProperties.getSign().setNoncePrefix("custom:sign:nonce:");
        ApiSignAspect customAspect = new ApiSignAspect(
                hmacSigner,
                new DefaultSignKeyProvider(webProperties.getSign()),
                customFactory.getBeanProvider(IdempotentKeyStore.class),
                objectMapper,
                webProperties
        );

        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = "nonce-prefix-001";
        String canonical = SignCanonicalUtil.buildRequestCanonicalString(
                "GET", "/api/orders/hmac", timestamp, nonce, Map.of(), DigestUtil.sha256Hex("")
        );
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders/hmac");
        request.addHeader(ApiSignAspect.HEADER_TIMESTAMP, timestamp);
        request.addHeader(ApiSignAspect.HEADER_NONCE, nonce);
        request.addHeader(ApiSignAspect.HEADER_SIGNATURE, hmacSigner.sign(canonical, HMAC_SECRET));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, new MockHttpServletResponse()));

        Method hmacMethod = SampleSignEndpoints.class.getMethod("hmacEndpoint");
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(hmacMethod);
        when(joinPoint.getTarget()).thenReturn(new SampleSignEndpoints());
        when(joinPoint.proceed()).thenReturn(Result.success("ok"));

        customAspect.around(joinPoint, hmacMethod.getAnnotation(ApiSign.class));
        verify(mockKeyStore).tryLock(eq("custom:sign:nonce:default:nonce-prefix-001"), anyLong(), any());
    }

    @Test
    @DisplayName("分布式 Nonce 存储异常时：failOpen=false 阻断并保留因果链，failOpen=true 降级内存防重放")
    void shouldHandleNonceKeyStoreFailureWithFailOpenPolicy() throws Throwable {
        IdempotentKeyStore brokenKeyStore = mock(IdempotentKeyStore.class);
        RuntimeException rootCause = new RuntimeException("Redis connection refused");
        when(brokenKeyStore.tryLock(anyString(), anyLong(), any())).thenThrow(rootCause);

        StaticListableBeanFactory brokenFactory = new StaticListableBeanFactory();
        brokenFactory.addBean("idempotentKeyStore", brokenKeyStore);

        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = "nonce-failover-" + UUID.randomUUID().toString().replace("-", "");
        String canonical = SignCanonicalUtil.buildRequestCanonicalString(
                "GET", "/api/orders/hmac", timestamp, nonce, Map.of(), DigestUtil.sha256Hex("")
        );
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders/hmac");
        request.addHeader(ApiSignAspect.HEADER_TIMESTAMP, timestamp);
        request.addHeader(ApiSignAspect.HEADER_NONCE, nonce);
        request.addHeader(ApiSignAspect.HEADER_SIGNATURE, hmacSigner.sign(canonical, HMAC_SECRET));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, new MockHttpServletResponse()));

        Method hmacMethod = SampleSignEndpoints.class.getMethod("hmacEndpoint");
        ApiSign apiSign = hmacMethod.getAnnotation(ApiSign.class);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Result<String> expectedBody = Result.success("ok");
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(hmacMethod);
        when(joinPoint.getTarget()).thenReturn(new SampleSignEndpoints());
        when(joinPoint.proceed()).thenReturn(expectedBody);

        // 1. failOpen=false (默认) 必须阻断并保留原始 cause
        webProperties.getSign().setFailOpen(false);
        ApiSignAspect failClosedAspect = new ApiSignAspect(
                hmacSigner,
                new DefaultSignKeyProvider(webProperties.getSign()),
                brokenFactory.getBeanProvider(IdempotentKeyStore.class),
                objectMapper,
                webProperties
        );
        assertThatThrownBy(() -> failClosedAspect.around(joinPoint, apiSign))
                .isInstanceOf(BusinessException.class)
                .hasCause(rootCause);

        // 2. failOpen=true 自动降级至本地内存防重放：首次放行，重放拦截
        webProperties.getSign().setFailOpen(true);
        ApiSignAspect failOpenAspect = new ApiSignAspect(
                hmacSigner,
                new DefaultSignKeyProvider(webProperties.getSign()),
                brokenFactory.getBeanProvider(IdempotentKeyStore.class),
                objectMapper,
                webProperties
        );
        assertThat(failOpenAspect.around(joinPoint, apiSign)).isEqualTo(expectedBody);
        assertThatThrownBy(() -> failOpenAspect.around(joinPoint, apiSign))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(CommonResultCode.REPEAT_SUBMIT.getCode());
    }

    static class SampleSignEndpoints {

        @ApiSign
        public Result<String> hmacEndpoint() {
            return Result.success("ok-hmac");
        }

        @ApiSign(algorithm = SignAlgorithm.RSA)
        public Result<String> rsaEndpoint() {
            return Result.success("ok-rsa");
        }

        @ApiSign(algorithm = SignAlgorithm.SM2)
        public Result<String> sm2Endpoint() {
            return Result.success("ok-sm2");
        }

        @ApiSign(verifyRequest = false, signResponse = true, algorithm = SignAlgorithm.RSA)
        public Result<String> rsaOutboundOnlyEndpoint() {
            return Result.success("ok-rsa-outbound");
        }
    }
}
