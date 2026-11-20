package com.codeguardian.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 外部集成接口鉴权拦截器
 *
 * <p>用于 CI/CD（Jenkins、GitLab CI、GitHub Actions）与 Webhook 调用。
 * 这些接口不走 Sa-Token 登录态，而是要求请求头携带共享令牌：</p>
 * <ul>
 *     <li>{@code X-API-Token}: CI/CD 流水线使用</li>
 *     <li>{@code X-Gitlab-Token}: GitLab/GitCode Webhook Secret</li>
 * </ul>
 *
 * <p>共享令牌通过环境变量 {@code API_TOKEN}（配置项
 * {@code app.integration.api-token}）设置；未配置时拒绝所有外部调用。</p>
 */
@Component
@Slf4j
public class IntegrationAuthInterceptor implements HandlerInterceptor {

    public static final String API_TOKEN_HEADER = "X-API-Token";
    public static final String GITLAB_TOKEN_HEADER = "X-Gitlab-Token";

    @Value("${app.integration.api-token:}")
    private String apiToken;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!StringUtils.hasText(apiToken)) {
            log.warn("API_TOKEN 未配置，拒绝外部集成请求: {}", request.getRequestURI());
            writeUnauthorized(response, "API_TOKEN 未配置，外部集成接口不可用");
            return false;
        }

        String providedToken = request.getHeader(API_TOKEN_HEADER);
        if (!StringUtils.hasText(providedToken)) {
            providedToken = request.getHeader(GITLAB_TOKEN_HEADER);
        }

        if (!constantTimeEquals(apiToken, providedToken)) {
            log.warn("外部集成请求 Token 无效: {}", request.getRequestURI());
            writeUnauthorized(response, "无效的 API Token");
            return false;
        }

        return true;
    }

    private boolean constantTimeEquals(String expected, String actual) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = (actual != null ? actual : "").getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(
                "{\"error\":\"未授权\",\"message\":\"" + message + "\",\"status\":401}");
    }
}
