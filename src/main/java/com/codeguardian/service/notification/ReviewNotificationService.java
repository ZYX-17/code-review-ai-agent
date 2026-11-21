package com.codeguardian.service.notification;

import com.codeguardian.entity.Finding;
import com.codeguardian.entity.ReviewTask;
import com.codeguardian.enums.SeverityEnum;
import com.codeguardian.enums.TaskStatusEnum;
import com.codeguardian.model.dto.SettingsDTO;
import com.codeguardian.repository.FindingRepository;
import com.codeguardian.service.SystemConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 审查结果通知服务
 *
 * <p>支持企业微信、钉钉、飞书群机器人与自定义 Webhook。
 * 通知配置保存在系统设置中，由管理员在设置页维护。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewNotificationService {

    private static final Set<String> CHANNELS = Set.of("wecom", "dingtalk", "feishu", "custom");

    private final SystemConfigService configService;
    private final FindingRepository findingRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.public-base-url:}")
    private String publicBaseUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * 异步发送审查结果通知，不阻塞审查任务收尾
     */
    public void notifyAsync(ReviewTask task) {
        if (task == null) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                send(task);
            } catch (Exception e) {
                log.warn("发送审查通知失败: taskId={}: {}", task.getId(), e.getMessage());
            }
        });
    }

    /**
     * 根据当前系统设置发送审查结果通知
     *
     * @return 是否实际发送成功；未启用或不符合发送条件时返回 false
     */
    public boolean send(ReviewTask task) {
        SettingsDTO settings = configService.getSettings();
        if (!Boolean.TRUE.equals(settings.getNotifyEnabled())) {
            return false;
        }
        if (!StringUtils.hasText(settings.getNotifyWebhookUrl())) {
            log.warn("通知已启用但未配置 Webhook 地址，跳过发送");
            return false;
        }

        boolean completed = TaskStatusEnum.COMPLETED.getValue().equals(task.getStatus());
        boolean failed = TaskStatusEnum.FAILED.getValue().equals(task.getStatus());
        if (!completed && !failed) {
            return false;
        }
        if (completed && Boolean.FALSE.equals(settings.getNotifyOnSuccess())) {
            return false;
        }
        if (failed && Boolean.FALSE.equals(settings.getNotifyOnFailure())) {
            return false;
        }

        Map<Integer, Integer> counts = countSeverities(task.getId());
        String text = buildText(task, counts);
        return dispatch(settings.getNotifyChannel(), settings.getNotifySecret(),
                settings.getNotifyWebhookUrl(), task, counts, text);
    }

    /**
     * 使用已保存的配置发送一条测试消息
     */
    public void sendTest() {
        SettingsDTO settings = configService.getSettings();
        if (!StringUtils.hasText(settings.getNotifyWebhookUrl())) {
            throw new IllegalArgumentException("请先填写 Webhook 地址并保存设置");
        }

        String text = "CodeGuardian AI 通知测试\n- 如果你收到这条消息，说明通知配置已生效。";
        boolean sent = dispatch(settings.getNotifyChannel(), settings.getNotifySecret(),
                settings.getNotifyWebhookUrl(), null, Map.of(), text);
        if (!sent) {
            throw new IllegalStateException("通知发送失败，请检查 Webhook 地址、密钥和应用日志");
        }
    }

    private boolean dispatch(String channel, String secret, String url,
                             ReviewTask task, Map<Integer, Integer> counts, String text) {
        String normalized = normalizeChannel(channel);
        String target = url;
        Map<String, Object> payload;

        switch (normalized) {
            case "dingtalk" -> {
                target = buildDingTalkUrl(url, secret, System.currentTimeMillis());
                payload = Map.of(
                        "msgtype", "markdown",
                        "markdown", Map.of(
                                "title", "CodeGuardian AI 审查结果",
                                "text", text));
            }
            case "feishu" -> payload = Map.of(
                    "msg_type", "text",
                    "content", Map.of("text", text));
            case "custom" -> payload = buildCustomPayload(task, counts, text);
            default -> payload = Map.of(
                    "msgtype", "markdown",
                    "markdown", Map.of("content", text));
        }

        return postJson(target, payload);
    }

    private Map<String, Object> buildCustomPayload(ReviewTask task, Map<Integer, Integer> counts,
                                                   String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (task == null) {
            payload.put("event", "test");
        } else {
            boolean failed = TaskStatusEnum.FAILED.getValue().equals(task.getStatus());
            payload.put("event", failed ? "review.failed" : "review.completed");
            payload.put("taskId", task.getId());
            payload.put("taskName", task.getName());
            payload.put("status", TaskStatusEnum.fromValue(task.getStatus()).name());
            payload.put("critical", count(counts, SeverityEnum.CRITICAL));
            payload.put("high", count(counts, SeverityEnum.HIGH));
            payload.put("medium", count(counts, SeverityEnum.MEDIUM));
            payload.put("low", count(counts, SeverityEnum.LOW));
            String reportUrl = buildReportUrl(task.getId());
            if (reportUrl != null) {
                payload.put("reportUrl", reportUrl);
            }
            if (failed && task.getErrorMessage() != null) {
                payload.put("errorMessage", task.getErrorMessage());
            }
        }
        payload.put("text", text);
        return payload;
    }

    String buildText(ReviewTask task, Map<Integer, Integer> counts) {
        boolean failed = TaskStatusEnum.FAILED.getValue().equals(task.getStatus());
        StringBuilder text = new StringBuilder();
        text.append(failed ? "CodeGuardian AI 审查失败" : "CodeGuardian AI 审查完成").append("\n");
        text.append("- 任务: ").append(task.getName()).append(" (#").append(task.getId()).append(")\n");
        if (failed) {
            text.append("- 原因: ")
                    .append(task.getErrorMessage() != null ? task.getErrorMessage() : "未知错误")
                    .append("\n");
        } else {
            text.append("- 问题: 严重 ").append(count(counts, SeverityEnum.CRITICAL))
                    .append(" / 高危 ").append(count(counts, SeverityEnum.HIGH))
                    .append(" / 中危 ").append(count(counts, SeverityEnum.MEDIUM))
                    .append(" / 低危 ").append(count(counts, SeverityEnum.LOW))
                    .append("\n");
        }
        String reportUrl = buildReportUrl(task.getId());
        if (reportUrl != null) {
            text.append("- 报告: ").append(reportUrl).append("\n");
        }
        return text.toString();
    }

    private Map<Integer, Integer> countSeverities(Long taskId) {
        Map<Integer, Integer> counts = new HashMap<>();
        if (taskId == null) {
            return counts;
        }
        try {
            List<Finding> findings = findingRepository.findByTaskId(taskId);
            if (findings != null) {
                for (Finding finding : findings) {
                    if (finding.getSeverity() != null) {
                        counts.merge(finding.getSeverity(), 1, Integer::sum);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("统计任务 {} 的问题数量失败: {}", taskId, e.getMessage());
        }
        return counts;
    }

    private int count(Map<Integer, Integer> counts, SeverityEnum severity) {
        return counts.getOrDefault(severity.getValue(), 0);
    }

    private String normalizeChannel(String channel) {
        if (!StringUtils.hasText(channel)) {
            return "wecom";
        }
        String value = channel.toLowerCase(Locale.ROOT);
        return CHANNELS.contains(value) ? value : "wecom";
    }

    static String buildDingTalkUrl(String url, String secret, long timestamp) {
        if (!StringUtils.hasText(secret)) {
            return url;
        }
        try {
            String stringToSign = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String sign = URLEncoder.encode(Base64.getEncoder().encodeToString(
                    mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8))), StandardCharsets.UTF_8);
            String separator = url.contains("?") ? "&" : "?";
            return url + separator + "timestamp=" + timestamp + "&sign=" + sign;
        } catch (Exception e) {
            log.warn("生成钉钉加签 URL 失败: {}", e.getMessage());
            return url;
        }
    }

    private boolean postJson(String url, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("通知发送失败: {} -> {} {}", url, response.statusCode(), response.body());
                return false;
            }
            if (!isBusinessSuccess(response.body())) {
                log.warn("通知被平台拒绝: {} -> {}", url, response.body());
                return false;
            }
            log.info("审查通知已发送: {}", url);
            return true;
        } catch (Exception e) {
            log.warn("通知发送异常: {}: {}", url, e.getMessage());
            return false;
        }
    }

    /**
     * 企业微信/钉钉返回 errcode，飞书返回 code，非 0 表示失败
     */
    private boolean isBusinessSuccess(String body) {
        if (!StringUtils.hasText(body)) {
            return true;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node.has("errcode")) {
                return node.path("errcode").asInt(0) == 0;
            }
            if (node.has("code")) {
                return node.path("code").asInt(0) == 0;
            }
            return true;
        } catch (Exception e) {
            // 自定义 Webhook 可能返回非 JSON，HTTP 2xx 即视为成功
            return true;
        }
    }

    private String buildReportUrl(Long taskId) {
        if (!StringUtils.hasText(publicBaseUrl) || taskId == null) {
            return null;
        }
        return publicBaseUrl.replaceAll("/+$", "") + "/report/" + taskId;
    }
}
