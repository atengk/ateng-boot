package io.github.atengk.boot.web.ready;

import io.github.atengk.boot.core.diagnostic.SlowBeanDiagnosticPostProcessor;
import io.github.atengk.boot.core.util.NetUtil;
import io.github.atengk.boot.web.autoconfigure.AtengWebAutoConfiguration;
import io.github.atengk.boot.web.properties.WebProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

import java.time.Duration;
import java.util.List;

/**
 * 应用就绪总览卡片监听器
 * 监听 {@link ApplicationReadyEvent}，在服务完全就绪后向控制台输出规整的双线 ASCII 艺术边框卡片
 *
 * @author Ateng
 * @since 2026-10-30
 */
public class ApplicationReadyCardListener implements ApplicationListener<ApplicationReadyEvent>, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ApplicationReadyCardListener.class);

    private static final int BORDER_CONTENT_WIDTH = 74;

    private final WebProperties webProperties;

    public ApplicationReadyCardListener(WebProperties webProperties) {
        this.webProperties = webProperties;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (webProperties != null && !webProperties.getReadyCard().isEnabled()) {
            return;
        }
        try {
            ApplicationContext context = event.getApplicationContext();
            Environment environment = context.getEnvironment();
            Duration timeTaken = event.getTimeTaken();
            String card = buildCard(context, environment, timeTaken);
            log.info("\n{}", card);
        } catch (Throwable ex) {
            log.debug("Failed to render application ready card", ex);
        }
    }

    /**
     * 构建双线 ASCII 艺术边框应用就绪总览卡片字符串
     *
     * @param context     Spring 应用上下文
     * @param environment Spring 运行时环境
     * @param timeTaken   应用启动耗时（若为 null 则自动回退）
     * @return 格式化后的 ASCII 卡片文本
     */
    public String buildCard(ApplicationContext context, Environment environment, Duration timeTaken) {
        String appName = environment.getProperty("spring.application.name", "application");
        String[] activeProfiles = environment.getActiveProfiles();
        String profileStr = (activeProfiles != null && activeProfiles.length > 0)
                ? String.join(", ", activeProfiles)
                : "default";

        String startupDurationStr;
        if (timeTaken != null) {
            startupDurationStr = String.format("%.2f s", timeTaken.toMillis() / 1000.0);
        } else {
            long uptime = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime();
            startupDurationStr = String.format("%.2f s", uptime / 1000.0);
        }

        // 端口与网络地址解析
        int port = resolvePort(context, environment);
        String contextPath = resolveContextPath(environment);
        boolean sslEnabled = environment.getProperty("server.ssl.enabled", Boolean.class, false)
                || environment.containsProperty("server.ssl.key-store");
        String protocol = sslEnabled ? "https" : "http";

        String localUrl = protocol + "://localhost:" + port + contextPath;
        String hostIp = NetUtil.getLocalIpv4();
        String networkUrl = protocol + "://" + hostIp + ":" + port + contextPath;

        String docPath = resolveDocPath(environment, context);
        String docUrl = (docPath != null) ? (networkUrl + docPath) : null;

        long pid = ProcessHandle.current().pid();
        String osInfo = System.getProperty("os.name") + " (" + System.getProperty("os.arch") + ")";
        String javaInfo = System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")";

        Runtime runtime = Runtime.getRuntime();
        long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long maxMemory = runtime.maxMemory() / (1024 * 1024);
        String heapInfo = usedMemory + "MB / " + maxMemory + "MB";

        String version = resolveFrameworkVersion();

        StringBuilder sb = new StringBuilder();
        // 顶部边框
        appendBorder(sb, '╔', '═', '╗');

        // ASCII 艺术标头与版本
        appendContentLine(sb, "    ___  _____ _____ _   _  ____");
        appendContentLine(sb, "   / _ \\_   _| ____| \\ | |/ ___|   :: Ateng Boot :: (v" + version + ")");
        appendContentLine(sb, "  / /_\\ \\| | |  _| |  \\| | |  _");
        appendContentLine(sb, " / /   \\ \\ | | |___| |\\  | |_| |");
        appendContentLine(sb, "/_/     \\_\\_| |_____|_| \\_|\\____|");

        // 分割线
        appendBorder(sb, '╠', '═', '╣');

        // 应用与网络概览
        appendContentLine(sb, "Application   : " + appName);
        appendContentLine(sb, "Active Profile: " + profileStr);
        appendContentLine(sb, "Startup Time  : " + startupDurationStr);
        appendContentLine(sb, "Local URL     : " + localUrl);
        appendContentLine(sb, "Network URL   : " + networkUrl);
        if (docUrl != null) {
            appendContentLine(sb, "Doc URL       : " + docUrl);
        }

        // 分割线
        appendBorder(sb, '╠', '═', '╣');

        // 运行时与环境信息
        appendContentLine(sb, "Process PID   : " + pid);
        appendContentLine(sb, "OS / Arch     : " + osInfo);
        appendContentLine(sb, "Java Runtime  : " + javaInfo);
        appendContentLine(sb, "Heap Memory   : " + heapInfo);

        // 慢 Bean 诊断（若存在）
        List<SlowBeanDiagnosticPostProcessor.SlowBeanInfo> slowBeans = SlowBeanDiagnosticPostProcessor.getSlowBeans();
        if (slowBeans != null && !slowBeans.isEmpty()) {
            SlowBeanDiagnosticPostProcessor.SlowBeanInfo first = slowBeans.get(0);
            appendContentLine(sb, "Slow Beans    : " + slowBeans.size() + " detected (e.g. '" + first.beanName() + "' [" + first.costMillis() + "ms])");
        }

        // 底部边框
        appendBorder(sb, '╚', '═', '╝');

        return sb.toString();
    }

    /**
     * 智能弱引用探测 Knife4j 或 SpringDoc 文档路径
     *
     * @param environment Spring 运行时环境
     * @param context     Spring 应用上下文
     * @return 文档上下文相对路径，未引入或未启用时返回 null
     */
    public static String resolveDocPath(Environment environment, ApplicationContext context) {
        ClassLoader classLoader = (context != null) ? context.getClassLoader() : ClassUtils.getDefaultClassLoader();
        return DocDetectionHelper.resolveDocPath(environment, classLoader);
    }

    /**
     * 解析 Web 服务实际运行端口
     *
     * @param context     Spring 应用上下文
     * @param environment Spring 运行时环境
     * @return 端口号
     */
    public static int resolvePort(ApplicationContext context, Environment environment) {
        int port = -1;
        if (context instanceof WebServerApplicationContext webServerContext) {
            try {
                port = webServerContext.getWebServer().getPort();
            } catch (Throwable ignored) {
            }
        }
        if (port <= 0) {
            port = environment.getProperty("local.server.port", Integer.class,
                    environment.getProperty("server.port", Integer.class, 8080));
        }
        return port > 0 ? port : 8080;
    }

    /**
     * 解析应用上下文 ContextPath
     *
     * @param environment Spring 运行时环境
     * @return 规整后的 ContextPath
     */
    public static String resolveContextPath(Environment environment) {
        String contextPath = environment.getProperty("server.servlet.context-path", "");
        if (contextPath == null || contextPath.isBlank() || "/".equals(contextPath.trim())) {
            return "";
        }
        contextPath = contextPath.trim();
        if (!contextPath.startsWith("/")) {
            contextPath = "/" + contextPath;
        }
        if (contextPath.endsWith("/")) {
            contextPath = contextPath.substring(0, contextPath.length() - 1);
        }
        return contextPath;
    }

    /**
     * 解析框架 ImplementationVersion
     *
     * @return 版本号字符串
     */
    public static String resolveFrameworkVersion() {
        String version = AtengWebAutoConfiguration.class.getPackage().getImplementationVersion();
        if (version != null && !version.isBlank()) {
            return version;
        }
        return "1.2.0";
    }

    private void appendBorder(StringBuilder sb, char left, char mid, char right) {
        sb.append(left);
        for (int i = 0; i < BORDER_CONTENT_WIDTH + 2; i++) {
            sb.append(mid);
        }
        sb.append(right).append("\n");
    }

    private void appendContentLine(StringBuilder sb, String line) {
        sb.append("║ ").append(padRight(line, BORDER_CONTENT_WIDTH)).append(" ║\n");
    }

    private String padRight(String str, int targetDisplayWidth) {
        if (str == null) {
            str = "";
        }
        int displayWidth = getDisplayWidth(str);
        if (displayWidth >= targetDisplayWidth) {
            return str;
        }
        StringBuilder sb = new StringBuilder(str);
        int padding = targetDisplayWidth - displayWidth;
        for (int i = 0; i < padding; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private int getDisplayWidth(String str) {
        int width = 0;
        for (int i = 0; i < str.length(); i++) {
            char ch = str.charAt(i);
            if (ch <= 0x7F) {
                width += 1;
            } else if (Character.isHighSurrogate(ch) || Character.isLowSurrogate(ch)) {
                width += 1;
            } else if (isDoubleWidth(ch)) {
                width += 2;
            } else {
                width += 1;
            }
        }
        return width;
    }

    private boolean isDoubleWidth(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || block == Character.UnicodeBlock.GENERAL_PUNCTUATION;
    }
}
