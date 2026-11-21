package com.codeguardian.controller;

import com.codeguardian.dto.ReviewRequestDTO;
import com.codeguardian.dto.ReviewResponseDTO;
import com.codeguardian.service.ReviewService;
import com.codeguardian.service.integration.GitFeedbackService;
import com.codeguardian.service.integration.QualityGateService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Webhook 控制器
 * 处理 GitHub / GitLab / GitCode 的 Webhook 事件
 */
@RestController
@RequestMapping("/api/v1/webhook")
@RequiredArgsConstructor
@Slf4j
public class WebhookController {

    private final ReviewService reviewService;
    private final GitFeedbackService gitFeedbackService;
    private final QualityGateService qualityGateService;
    private final ObjectMapper objectMapper;

    @Value("${app.integration.api-token:}")
    private String webhookSecret;

    /**
     * 处理 GitCode (GitLab compatible) Webhook
     * 关注事件: Merge Request Hook
     */
    @PostMapping("/gitcode")
    public ResponseEntity<String> handleGitCodeWebhook(
            @RequestHeader(value = "X-Gitlab-Event", defaultValue = "") String eventType,
            @RequestBody Map<String, Object> payload) {

        log.info("收到 GitCode Webhook 事件: {}", eventType);

        String objectKind = (String) payload.get("object_kind");
        if (!"merge_request".equals(objectKind)) {
            return ResponseEntity.ok("忽略的事件类型: " + objectKind);
        }

        Map<String, Object> attributes = (Map<String, Object>) payload.get("object_attributes");
        if (attributes == null) {
            return ResponseEntity.badRequest().body("无效的请求体: 缺少 object_attributes");
        }

        String action = (String) attributes.get("action");
        if (!"open".equals(action) && !"update".equals(action) && !"reopen".equals(action)) {
            return ResponseEntity.ok("忽略的操作: " + action);
        }

        CompletableFuture.runAsync(() -> processGitLabMr(payload));
        return ResponseEntity.ok("Webhook 已接收并开始处理。");
    }

    /**
     * 处理 GitLab 原生 Webhook（payload 与 GitCode 兼容）
     */
    @PostMapping("/gitlab")
    public ResponseEntity<String> handleGitLabWebhook(
            @RequestHeader(value = "X-Gitlab-Event", defaultValue = "") String eventType,
            @RequestBody Map<String, Object> payload) {
        log.info("收到 GitLab Webhook 事件: {}", eventType);
        return handleGitCodeWebhook(eventType, payload);
    }

    /**
     * 处理 GitHub Webhook
     *
     * <p>使用 X-Hub-Signature-256 校验，密钥为 API_TOKEN。</p>
     */
    @PostMapping("/github")
    public ResponseEntity<String> handleGitHubWebhook(
            @RequestHeader(value = "X-GitHub-Event", defaultValue = "") String eventType,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody byte[] rawBody) {

        if (!verifyGitHubSignature(rawBody, signature)) {
            return ResponseEntity.status(401).body("GitHub 签名校验失败");
        }

        try {
            JsonNode payload = objectMapper.readTree(rawBody);

            if ("pull_request".equals(eventType)) {
                String action = payload.path("action").asText();
                if (!List.of("opened", "synchronize", "reopened").contains(action)) {
                    return ResponseEntity.ok("忽略的操作: " + action);
                }

                JsonNode pr = payload.path("pull_request");
                String headRepoUrl = pr.path("head").path("repo").path("clone_url").asText(null);
                String gitUrl = StringUtils.hasText(headRepoUrl)
                        ? headRepoUrl
                        : payload.path("repository").path("clone_url").asText(null);
                String branch = pr.path("head").path("ref").asText(null);
                String commit = pr.path("head").path("sha").asText(null);
                int number = pr.path("number").asInt();
                String title = pr.path("title").asText("");

                log.info("收到 GitHub PR 事件: #{} {} (branch={}, commit={})", number, title, branch, commit);
                CompletableFuture.runAsync(() ->
                        triggerReview(gitUrl, branch, commit, "PR-" + number + "-" + title));

            } else if ("push".equals(eventType)) {
                String gitUrl = payload.path("repository").path("clone_url").asText(null);
                String commit = payload.path("after").asText(null);
                String ref = payload.path("ref").asText("");
                String branch = ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length()) : null;

                if (commit != null && !commit.matches("0+")) {
                    String shortCommit = commit.substring(0, Math.min(7, commit.length()));
                    log.info("收到 GitHub Push 事件: branch={}, commit={}", branch, shortCommit);
                    CompletableFuture.runAsync(() ->
                            triggerReview(gitUrl, branch, commit, "PUSH-" + shortCommit));
                }
            } else {
                return ResponseEntity.ok("忽略的事件类型: " + eventType);
            }

            return ResponseEntity.ok("Webhook 已接收并开始处理。");
        } catch (Exception e) {
            log.error("处理 GitHub Webhook 失败", e);
            return ResponseEntity.badRequest().body("无效的请求体: " + e.getMessage());
        }
    }

    private void processGitLabMr(Map<String, Object> payload) {
        try {
            Map<String, Object> attributes = (Map<String, Object>) payload.get("object_attributes");
            Map<String, Object> project = (Map<String, Object>) payload.get("project");

            if (attributes == null || project == null) return;

            String gitUrl = (String) project.get("git_http_url");
            String htmlUrl = (String) project.get("web_url");
            String branch = (String) attributes.get("source_branch");

            Map<String, Object> lastCommit = (Map<String, Object>) attributes.get("last_commit");
            String commitSha = lastCommit != null ? (String) lastCommit.get("id") : null;

            Integer mrIid = (Integer) attributes.get("iid");
            String mrTitle = (String) attributes.get("title");

            log.info("正在处理 GitLab MR: {}/merge_requests/{} (branch: {}, commit: {})",
                    htmlUrl, mrIid, branch, commitSha);

            if (commitSha != null) {
                gitFeedbackService.updateStatus(gitUrl, commitSha, "pending", "CodeGuardian AI 审查中...");
            }

            ReviewResponseDTO response = triggerReview(
                    gitUrl, branch, commitSha, "MR-" + mrIid + "-" + mrTitle);

            gitFeedbackService.postComment(gitUrl, String.valueOf(mrIid),
                    "🤖 **CodeGuardian AI** 已开始审查此 MR。\n\n" +
                    "任务 ID: `" + response.getTaskId() + "`\n" +
                    "请等待后续审查报告。");

            awaitAndReportResult(gitUrl, commitSha, mrIid, response.getTaskId());

        } catch (Exception e) {
            log.error("处理 GitLab Webhook 失败", e);
        }
    }

    private ReviewResponseDTO triggerReview(String gitUrl, String branch, String commit, String taskName) {
        ReviewRequestDTO request = ReviewRequestDTO.builder()
                .reviewType("GIT")
                .gitUrl(gitUrl)
                .gitBranch(branch)
                .gitCommit(commit)
                .taskName(taskName)
                .build();

        ReviewResponseDTO response = reviewService.createReviewTask(request);
        log.info("Webhook 触发审查: taskId={}, branch={}, commit={}",
                response.getTaskId(), branch, commit);
        return response;
    }

    /**
     * 校验 GitHub X-Hub-Signature-256
     */
    private boolean verifyGitHubSignature(byte[] payload, String signature) {
        if (!StringUtils.hasText(webhookSecret)
                || !StringUtils.hasText(signature)
                || !signature.startsWith("sha256=")) {
            return false;
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = HexFormat.of().formatHex(mac.doFinal(payload));
            byte[] expectedBytes = expected.getBytes(StandardCharsets.US_ASCII);
            byte[] actualBytes = signature.substring("sha256=".length())
                    .toLowerCase(Locale.ROOT)
                    .getBytes(StandardCharsets.US_ASCII);
            return MessageDigest.isEqual(expectedBytes, actualBytes);
        } catch (Exception e) {
            log.warn("GitHub 签名校验失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 轮询任务状态，完成后回写评论与提交状态
     */
    private void awaitAndReportResult(String gitUrl, String commitSha, Integer mrIid, Long taskId) {
        ReviewResponseDTO finalTask = null;

        try {
            // 最多等待 10 分钟（120 * 5s）
            for (int i = 0; i < 120; i++) {
                finalTask = reviewService.getReviewTask(taskId);
                String status = finalTask.getStatus();
                if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                    break;
                }
                Thread.sleep(5000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("等待审查任务 {} 被中断", taskId);
            return;
        } catch (Exception e) {
            log.error("轮询审查任务 {} 失败", taskId, e);
            return;
        }

        if (finalTask == null) {
            return;
        }

        if ("FAILED".equals(finalTask.getStatus())) {
            String reason = finalTask.getErrorMessage() != null
                    ? finalTask.getErrorMessage() : "未知错误";
            if (commitSha != null) {
                gitFeedbackService.updateStatus(gitUrl, commitSha, "failed", "CodeGuardian AI 审查失败: " + reason);
            }
            gitFeedbackService.postComment(gitUrl, String.valueOf(mrIid),
                    "❌ **CodeGuardian AI** 审查失败。\n\n" +
                    "任务 ID: `" + taskId + "`\n" +
                    "原因: " + reason);
            return;
        }

        boolean passed = qualityGateService.checkQualityGate(taskId, "CRITICAL");
        String summary = String.format("严重 %d / 高危 %d / 中危 %d / 低危 %d",
                finalTask.getCriticalCount(), finalTask.getHighCount(),
                finalTask.getMediumCount(), finalTask.getLowCount());

        if (commitSha != null) {
            gitFeedbackService.updateStatus(gitUrl, commitSha,
                    passed ? "success" : "failed",
                    "CodeGuardian AI 审查完成，问题：" + summary);
        }

        gitFeedbackService.postComment(gitUrl, String.valueOf(mrIid),
                "🤖 **CodeGuardian AI** 审查完成。\n\n" +
                "- 任务 ID: `" + taskId + "`\n" +
                "- 问题统计: " + summary + "\n" +
                "- 门禁（CRITICAL）: " + (passed ? "通过" : "未通过") + "\n" +
                "- 报告: `/report/" + taskId + "`");
    }
}
