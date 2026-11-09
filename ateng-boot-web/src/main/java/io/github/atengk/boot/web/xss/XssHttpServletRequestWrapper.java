package io.github.atengk.boot.web.xss;

import io.github.atengk.boot.web.properties.WebProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * XSS 安全清洗 HTTP 请求包装器
 * 拦截 Query 参数、Form 表单参数以及可选的非豁免 HTTP 请求头，实施双轨无害化清洗
 *
 * @author Ateng
 * @since 2026-10-27
 */
public class XssHttpServletRequestWrapper extends HttpServletRequestWrapper {

    private final XssCleaner cleaner;
    private final boolean cleanHeaders;
    private final Set<String> excludeHeaders;

    public XssHttpServletRequestWrapper(HttpServletRequest request) {
        this(request, new XssCleaner(), null);
    }

    public XssHttpServletRequestWrapper(HttpServletRequest request, XssCleaner cleaner) {
        this(request, cleaner, null);
    }

    public XssHttpServletRequestWrapper(HttpServletRequest request, XssCleaner cleaner, WebProperties.XssProperties properties) {
        super(request);
        this.cleaner = cleaner != null ? cleaner : new XssCleaner();
        if (properties != null && properties.isCleanHeaders()) {
            this.cleanHeaders = true;
            Set<String> excludes = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            if (properties.getExcludeHeaders() != null) {
                excludes.addAll(properties.getExcludeHeaders());
            }
            this.excludeHeaders = Collections.unmodifiableSet(excludes);
        } else {
            this.cleanHeaders = false;
            this.excludeHeaders = Collections.emptySet();
        }
    }

    @Override
    public String getHeader(String name) {
        String value = super.getHeader(name);
        if (!cleanHeaders || value == null || isHeaderExcluded(name)) {
            return value;
        }
        return cleaner.clean(value);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        Enumeration<String> headers = super.getHeaders(name);
        if (!cleanHeaders || headers == null || isHeaderExcluded(name)) {
            return headers;
        }
        List<String> list = new ArrayList<>();
        while (headers.hasMoreElements()) {
            list.add(cleaner.clean(headers.nextElement()));
        }
        return Collections.enumeration(list);
    }

    private boolean isHeaderExcluded(String name) {
        return name == null || excludeHeaders.contains(name);
    }

    @Override
    public String getParameter(String name) {
        String value = super.getParameter(name);
        return cleaner.clean(value);
    }

    @Override
    public String[] getParameterValues(String name) {
        String[] values = super.getParameterValues(name);
        if (values == null) {
            return null;
        }
        String[] cleaned = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            cleaned[i] = cleaner.clean(values[i]);
        }
        return cleaned;
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        Map<String, String[]> original = super.getParameterMap();
        if (original == null || original.isEmpty()) {
            return original;
        }
        Map<String, String[]> cleaned = new LinkedHashMap<>(original.size());
        for (Map.Entry<String, String[]> entry : original.entrySet()) {
            String[] values = entry.getValue();
            if (values == null) {
                cleaned.put(entry.getKey(), null);
            } else {
                String[] cleanedValues = new String[values.length];
                for (int i = 0; i < values.length; i++) {
                    cleanedValues[i] = cleaner.clean(values[i]);
                }
                cleaned.put(entry.getKey(), cleanedValues);
            }
        }
        return Collections.unmodifiableMap(cleaned);
    }

    public XssCleaner getCleaner() {
        return cleaner;
    }

    public boolean isCleanHeaders() {
        return cleanHeaders;
    }

    public Set<String> getExcludeHeaders() {
        return excludeHeaders;
    }
}
