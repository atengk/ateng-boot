package io.github.atengk.boot.social.test;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.context.model.SaCookie;
import cn.dev33.satoken.context.model.SaRequest;
import cn.dev33.satoken.context.model.SaResponse;
import cn.dev33.satoken.context.model.SaStorage;
import cn.dev33.satoken.context.model.SaTokenContextModelBox;
import cn.dev33.satoken.stp.StpUtil;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 离线单测环境 Sa-Token 上下文装配与清理辅助工具
 *
 * @author Ateng
 * @since 2026-11-03
 */
public final class MockSaTokenTestHelper {

    private static SaTokenContext originalContext;
    private static final Map<String, Object> STORAGE_MAP = new ConcurrentHashMap<>();
    private static final Map<String, String> HEADER_MAP = new ConcurrentHashMap<>();
    private static final Map<String, String> COOKIE_MAP = new ConcurrentHashMap<>();

    private MockSaTokenTestHelper() {
    }

    public static void setUp() {
        STORAGE_MAP.clear();
        HEADER_MAP.clear();
        COOKIE_MAP.clear();

        if (originalContext == null) {
            originalContext = SaManager.getSaTokenContext();
        }

        SaManager.setConfig(new SaTokenConfig());

        SaRequest mockRequest = mock(SaRequest.class);
        when(mockRequest.getHeader(anyString())).thenAnswer(inv -> HEADER_MAP.get(inv.getArgument(0)));
        when(mockRequest.getCookieValue(anyString())).thenAnswer(inv -> COOKIE_MAP.get(inv.getArgument(0)));

        SaResponse mockResponse = mock(SaResponse.class);
        doAnswer(inv -> {
            String name = inv.getArgument(0);
            String value = inv.getArgument(1);
            if (name != null && value != null) {
                HEADER_MAP.put(name, value);
            }
            return null;
        }).when(mockResponse).setHeader(anyString(), anyString());

        doAnswer(inv -> {
            SaCookie cookie = inv.getArgument(0);
            if (cookie != null && cookie.getName() != null) {
                if (cookie.getMaxAge() == 0) {
                    COOKIE_MAP.remove(cookie.getName());
                } else {
                    COOKIE_MAP.put(cookie.getName(), cookie.getValue());
                }
            }
            return null;
        }).when(mockResponse).addCookie(any());

        SaStorage mockStorage = mock(SaStorage.class);
        when(mockStorage.get(anyString())).thenAnswer(inv -> STORAGE_MAP.get(inv.getArgument(0)));
        doAnswer(inv -> {
            STORAGE_MAP.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(mockStorage).set(anyString(), any());

        SaTokenContext testContext = new SaTokenContext() {
            @Override
            public void setContext(SaRequest req, SaResponse res, SaStorage sto) {
            }

            @Override
            public void clearContext() {
            }

            @Override
            public SaRequest getRequest() {
                return mockRequest;
            }

            @Override
            public SaResponse getResponse() {
                return mockResponse;
            }

            @Override
            public SaStorage getStorage() {
                return mockStorage;
            }

            @Override
            public boolean isValid() {
                return true;
            }

            @Override
            public SaTokenContextModelBox getModelBox() {
                return new SaTokenContextModelBox(mockRequest, mockResponse, mockStorage);
            }
        };

        SaManager.setSaTokenContext(testContext);
    }

    public static void tearDown() {
        try {
            StpUtil.logout();
        } catch (Exception ignored) {
        }
        if (originalContext != null) {
            SaManager.setSaTokenContext(originalContext);
        }
        STORAGE_MAP.clear();
        HEADER_MAP.clear();
        COOKIE_MAP.clear();
    }
}
