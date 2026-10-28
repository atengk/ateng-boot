package io.github.atengk.boot.admin.controller;

import cn.dev33.satoken.stp.StpUtil;
import de.codecentric.boot.admin.server.config.AdminServerProperties;
import io.github.atengk.boot.admin.autoconfigure.AtengAdminServerAutoConfiguration;
import io.github.atengk.boot.admin.provider.AdminAuthProvider;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;

/**
 * 专职负责 SBA 原生表单登录渲染与 Sa-Token 鉴权适配的极简控制器
 *
 * @author Ateng
 * @since 2026-10-26
 */
@Controller
public class AdminLoginController {

    private static final String HTML_TEMPLATE = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Spring Boot Admin 控制台登录</title>
                <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif; background: #f0f2f5; display: flex; align-items: center; justify-content: center; min-height: 100vh; }
                    .login-card { background: #fff; width: 380px; padding: 36px; border-radius: 12px; box-shadow: 0 8px 24px rgba(0,0,0,0.08); }
                    .logo { text-align: center; margin-bottom: 24px; }
                    .logo h2 { font-size: 22px; color: #1f2937; margin-bottom: 6px; }
                    .logo p { font-size: 13px; color: #6b7280; }
                    .alert { padding: 10px 14px; border-radius: 6px; font-size: 13px; margin-bottom: 18px; }
                    .alert-danger { background: #fee2e2; color: #b91c1c; border: 1px solid #fecaca; }
                    .alert-success { background: #dcfce7; color: #15803d; border: 1px solid #bbf7d0; }
                    .form-group { margin-bottom: 18px; }
                    .form-group label { display: block; font-size: 13px; color: #374151; margin-bottom: 6px; font-weight: 500; }
                    .form-group input { width: 100%; height: 40px; padding: 0 12px; border: 1px solid #d1d5db; border-radius: 6px; font-size: 14px; outline: none; transition: border-color 0.2s; }
                    .form-group input:focus { border-color: #3b82f6; ring: 2px solid #93c5fd; }
                    .btn-submit { width: 100%; height: 42px; background: #2563eb; color: #fff; border: none; border-radius: 6px; font-size: 15px; font-weight: 500; cursor: pointer; transition: background 0.2s; margin-top: 8px; }
                    .btn-submit:hover { background: #1d4ed8; }
                    .footer { text-align: center; margin-top: 24px; font-size: 12px; color: #9ca3af; }
                </style>
            </head>
            <body>
                <div class="login-card">
                    <div class="logo">
                        <h2>ateng-boot Admin</h2>
                        <p>微服务统一运维与看板中心</p>
                    </div>
                    {{ALERT_HTML}}
                    <form action='{{FORM_ACTION}}' method='post'>
                        <div class="form-group">
                            <label for="username">运维管理员账号</label>
                            <input type="text" id="username" name="username" placeholder="请输入用户名" required autofocus>
                        </div>
                        <div class="form-group">
                            <label for="password">运维访问口令</label>
                            <input type="password" id="password" name="password" placeholder="请输入密码" required>
                        </div>
                        <button type="submit" class="btn-submit">登 录</button>
                    </form>
                    <div class="footer">&copy; 2026 ateng-boot 工业级可观测套件</div>
                </div>
            </body>
            </html>
            """;

    private final AdminAuthProvider authProvider;
    private final String adminPath;

    public AdminLoginController(AdminAuthProvider authProvider, AdminServerProperties adminServerProperties) {
        this.authProvider = authProvider;
        this.adminPath = AtengAdminServerAutoConfiguration.normalizeContextPath(
                adminServerProperties != null ? adminServerProperties.getContextPath() : null
        );
    }

    @GetMapping(value = {"/login", "${spring.boot.admin.context-path:}/login"}, produces = "text/html;charset=UTF-8")
    @ResponseBody
    public String renderLoginPage(@RequestParam(value = "error", required = false) String error,
                                  @RequestParam(value = "logout", required = false) String logout) {
        return renderHtmlTemplate(error != null, logout != null);
    }

    @PostMapping(value = {"/login", "${spring.boot.admin.context-path:}/login"}, consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public void doLogin(@RequestParam("username") String username,
                        @RequestParam("password") String password,
                        HttpServletResponse response) throws IOException {
        if (authProvider.authenticate(username, password)) {
            // 登录成功：植入 Sa-Token 会话，302 重定向至控制台首页
            StpUtil.login(username);
            String target = adminPath.isEmpty() ? "/" : adminPath;
            response.sendRedirect(target);
        } else {
            // 认证失败：302 重定向回登录页并携带 error 标识
            String target = adminPath.isEmpty() ? "/login?error=true" : (adminPath + "/login?error=true");
            response.sendRedirect(target);
        }
    }

    @RequestMapping(value = {"/logout", "${spring.boot.admin.context-path:}/logout"}, method = {RequestMethod.POST, RequestMethod.GET})
    public void doLogout(HttpServletResponse response) throws IOException {
        StpUtil.logout();
        String target = adminPath.isEmpty() ? "/login?logout=true" : (adminPath + "/login?logout=true");
        response.sendRedirect(target);
    }

    private String renderHtmlTemplate(boolean hasError, boolean hasLogout) {
        String alertHtml = "";
        if (hasError) {
            alertHtml = "<div class='alert alert-danger'>账号或密码错误，请重新输入</div>";
        } else if (hasLogout) {
            alertHtml = "<div class='alert alert-success'>您已安全退出控制台</div>";
        }

        String formAction = adminPath.isEmpty() ? "/login" : (adminPath + "/login");

        return HTML_TEMPLATE
                .replace("{{ALERT_HTML}}", alertHtml)
                .replace("{{FORM_ACTION}}", formAction);
    }
}
