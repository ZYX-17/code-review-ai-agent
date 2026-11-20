package com.codeguardian.aspect;

import cn.dev33.satoken.stp.StpUtil;
import com.codeguardian.annotation.OperationLogRecord;
import com.codeguardian.entity.OperationLog;
import com.codeguardian.entity.User;
import com.codeguardian.repository.OperationLogRepository;
import com.codeguardian.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 操作日志切面
 *
 * <p>拦截标注了 {@link OperationLogRecord} 的方法，记录操作人、参数、耗时、IP 与结果。</p>
 */
@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class OperationLogAspect {

    private static final Pattern SENSITIVE_PATTERN = Pattern.compile(
            "(\"(?:password|oldPassword|newPassword|apiKey|secretKey|token|gitPassword)\"\\s*:\\s*)\"[^\"]*\"",
            Pattern.CASE_INSENSITIVE);

    private static final int MAX_PARAMS_LENGTH = 2000;

    private final OperationLogRepository operationLogRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Around("@annotation(operationLogRecord)")
    public Object around(ProceedingJoinPoint joinPoint, OperationLogRecord operationLogRecord) throws Throwable {
        long startTime = System.currentTimeMillis();
        boolean success = false;
        String errorMessage = null;

        try {
            Object result = joinPoint.proceed();
            success = true;
            return result;
        } catch (Throwable throwable) {
            errorMessage = throwable.getMessage();
            throw throwable;
        } finally {
            saveLog(joinPoint, operationLogRecord, startTime, success, errorMessage);
        }
    }

    private void saveLog(ProceedingJoinPoint joinPoint, OperationLogRecord annotation,
                         long startTime, boolean success, String errorMessage) {
        try {
            HttpServletRequest request = currentRequest();
            Long userId = null;
            String username = null;

            try {
                if (StpUtil.isLogin()) {
                    userId = StpUtil.getLoginIdAsLong();
                }
            } catch (Exception ignored) {
                // 未登录或 Token 无效时忽略
            }

            if (request != null) {
                HttpSession session = request.getSession(false);
                if (session != null && session.getAttribute("username") != null) {
                    username = String.valueOf(session.getAttribute("username"));
                }
            }

            // API 登录（非表单登录）没有写入 Session，从数据库补全用户名
            if (username == null && userId != null) {
                username = userRepository.findById(userId)
                        .map(User::getUsername)
                        .orElse(null);
            }

            OperationLog operationLog = OperationLog.builder()
                    .userId(userId)
                    .username(username)
                    .operation(annotation.module() + " - " + annotation.action())
                    .method(joinPoint.getSignature().toShortString())
                    .params(serializeArgs(joinPoint.getArgs()))
                    .timeMillis(System.currentTimeMillis() - startTime)
                    .ip(resolveClientIp(request))
                    .status(success ? 0 : 1)
                    .errorMsg(truncate(errorMessage, MAX_PARAMS_LENGTH))
                    .createdAt(LocalDateTime.now())
                    .build();

            operationLogRepository.save(operationLog);
        } catch (Exception e) {
            log.warn("保存操作日志失败: {}", e.getMessage());
        }
    }

    private String serializeArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return "";
        }

        Object[] loggableArgs = Arrays.stream(args)
                .filter(arg -> !(arg instanceof HttpServletRequest))
                .filter(arg -> !(arg instanceof HttpServletResponse))
                .filter(arg -> !(arg instanceof HttpSession))
                .toArray();

        try {
            String json = objectMapper.writeValueAsString(loggableArgs);
            json = SENSITIVE_PATTERN.matcher(json).replaceAll("$1\"***\"");
            return truncate(json, MAX_PARAMS_LENGTH);
        } catch (Exception e) {
            return Arrays.stream(loggableArgs)
                    .map(arg -> arg == null ? "null" : arg.getClass().getSimpleName())
                    .collect(Collectors.joining(", "));
        }
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    private String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }
}
