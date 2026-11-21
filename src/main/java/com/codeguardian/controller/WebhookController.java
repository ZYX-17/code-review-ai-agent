package com.codeguardian.controller;

import com.codeguardian.dto.ReviewRequestDTO;
import com.codeguardian.dto.ReviewResponseDTO;
import com.codeguardian.entity.Finding;
import com.codeguardian.enums.SeverityEnum;
import com.codeguardian.repository.FindingRepository;
import com.codeguardian.service.ReviewService;
import com.codeguardian.service.integration.GitFeedbackService;
import com.codeguardian.service.integration.GitHubFeedbackService;
import com.codeguardian.service.integration.GitLabFeedbackService;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private final GitHubFeedbackService githubFeedbackService;
    private final GitLabFeedbackService gitLabFeedbackService;
    private final FindingRepository findingRepository;

    @Value("${app.integration.api-token:}")
    private String webhookSecret;

    @Value("${app.public-base-url:}")
    private String publicBaseUrl;

    /**
     * 处理 GitCode (GitLab compatible) Webhook
     * 关注事件: Merge Request Hook、Push Hook
     */
    @PostMapping("/gitcode")
    public ResponseEntity<String> handleGitCodeWebhook(
            @RequestHeader(value = "X-Gitlab-Event", defaultValue = "") String eventType,
            @RequestBody Map<String, Object> payload) {
        log.info("收到 GitCode Webhook 事件: {}", eventType);
        return handleGitLabCompatibleWebhook(payload, false);
    }

    /**
     * 处理 GitLab 原生 Webhook（payload 与 GitCode 兼容）
     *
     * <p>配置 gitlab.api.token 后使用 GitLab API 回写 MR 评论与 commit status；
     * 未配置时回退到 GitCode Token。</p>
     */
    @PostMapping("/gitlab")
    public ResponseEntity<String> handleGitLabWebhook(
            @RequestHeader(value = "X-Gitlab-Event", defaultValue = "") String eventType,
            @RequestBody Map<String, Object> payload) {
        log.info("收到 GitLab Webhook 事件: {}", eventType);
        return handleGitLabCompatibleWebhook(payload, true);
    }

    private ResponseEntity<String> handleGitLabCompatibleWebhook(Map<String, Object> payload,
                                                                 boolean nativeGitLab) {
        String objectKind = (String) payload.get("object_kind");

        if ("push".equals(objectKind)) {
            CompletableFuture.runAsync(() -> processGitLabPush(payload, nativeGitLab));
            return ResponseEntity.ok("Push 事件已接收并开始处理。");
        }

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

        CompletableFuture.runAsync(() -> processGitLabMr(payload, nativeGitLab));
        return ResponseEntity.ok("Webhook 已接收并开始处理。");
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
                String repoFullName = payload.path("repository").path("full_name").asText(null);

                if (!StringUtils.hasText(repoFullName) || number <= 0
                        || !StringUtils.hasText(gitUrl) || !StringUtils.hasText(commit)) {
                    return ResponseEntity.badRequest().body("无效的 GitHub PR 事件");
                }

                log.info("收到 GitHub PR 事件: {} #{} {} (branch={}, commit={})",
                        repoFullName, number, title, branch, commit);
                CompletableFuture.runAsync(() ->
                        processGitHubPullRequest(repoFullName, number, gitUrl, branch, commit, title));

            } else if ("push".equals(eventType)) {
                String gitUrl = payload.path("repository").path("clone_url").asText(null);
                String repoFullName = payload.path("repository").path("full_name").asText(null);
                String commit = payload.path("after").asText(null);
                String ref = payload.path("ref").asText("");
                String branch = ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length()) : null;

                if (StringUtils.hasText(repoFullName) && StringUtils.hasText(commit) && !commit.matches("0+")) {
                    String shortCommit = commit.substring(0, Math.min(7, commit.length()));
                    List<String> changedFiles = collectGitHubPushChangedFiles(payload);
                    log.info("收到 GitHub Push 事件: {} branch={}, commit={}, changedFiles={}",
                            repoFullName, branch, shortCommit, changedFiles.size());
                    CompletableFuture.runAsync(() ->
                            processGitHubPush(repoFullName, gitUrl, branch, commit, shortCommit, changedFiles));
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

    private void processGitLabMr(Map<String, Object> payload, boolean nativeGitLab) {
        try {
            Map<String, Object> attributes = (Map<String, Object>) payload.get("object_attributes");
            Map<String, Object> project = (Map<String, Object>) payload.get("project");

            if (attributes == null || project == null) return;

            String gitUrl = (String) project.get("git_http_url");
            String htmlUrl = (String) project.get("web_url");
            String branch = (String) attributes.get("source_branch");

            Map<String, Object> lastCommit = (Map<String, Object>) attributes.get("last_commit");
            String commitSha = lastCommit != null ? (String) lastCommit.get("id") : null;

            Integer mrIid = attributes.get("iid") instanceof Number number ? number.intValue() : null;
            if (mrIid == null || mrIid <= 0) {
                log.warn("GitLab MR 事件缺少有效的 iid，已忽略");
                return;
            }
            String mrTitle = (String) attributes.get("title");

            log.info("正在处理 {} MR: {}/merge_requests/{} (branch: {}, commit: {})",
                    nativeGitLab ? "GitLab" : "GitCode", htmlUrl, mrIid, branch, commitSha);

            if (commitSha != null) {
                updatePlatformStatus(nativeGitLab, gitUrl, commitSha, "pending",
                        "CodeGuardian AI 审查中...");
            }

            ReviewResponseDTO response = triggerReview(
                    gitUrl, branch, commitSha, "MR-" + mrIid + "-" + mrTitle,
                    changedFilesForGitLabMr(nativeGitLab, gitUrl, mrIid));

            postPlatformComment(nativeGitLab, gitUrl, mrIid,
                    "🤖 **CodeGuardian AI** 已开始审查此 MR。\n\n" +
                    "任务 ID: `" + response.getTaskId() + "`\n" +
                    "请等待后续审查报告。");

            awaitAndReportResult(gitUrl, commitSha, mrIid, response.getTaskId(), nativeGitLab);

        } catch (Exception e) {
            log.error("处理 GitLab Webhook 失败", e);
        }
    }

    /**
     * 处理 GitLab / GitCode Push 事件：触发审查并回写 commit status
     */
    private void processGitLabPush(Map<String, Object> payload, boolean nativeGitLab) {
        try {
            Map<String, Object> project = (Map<String, Object>) payload.get("project");
            String gitUrl = project != null ? (String) project.get("git_http_url") : null;

            String ref = (String) payload.get("ref");
            String after = (String) payload.get("checkout_sha");
            if (after == null) {
                after = (String) payload.get("after");
            }

            if (gitUrl == null || ref == null || !ref.startsWith("refs/heads/")
                    || after == null || after.matches("0+")) {
                log.info("忽略无有效提交的 Push 事件: ref={}", ref);
                return;
            }

            String branch = ref.substring("refs/heads/".length());
            String shortCommit = after.substring(0, Math.min(7, after.length()));
            log.info("收到 {} Push 事件: {} branch={}, commit={}",
                    nativeGitLab ? "GitLab" : "GitCode", gitUrl, branch, shortCommit);

            updatePlatformStatus(nativeGitLab, gitUrl, after, "pending",
                    "CodeGuardian AI 审查中...");

            ReviewResponseDTO response = triggerReview(gitUrl, branch, after, "PUSH-" + shortCommit,
                    collectPushChangedFiles(payload));
            ReviewResponseDTO finalTask = awaitTaskCompletion(response.getTaskId());

            if (finalTask == null) {
                updatePlatformStatus(nativeGitLab, gitUrl, after, "failed",
                        "CodeGuardian AI 审查超时");
                return;
            }

            if ("FAILED".equals(finalTask.getStatus())) {
                String reason = finalTask.getErrorMessage() != null
                        ? finalTask.getErrorMessage() : "未知错误";
                updatePlatformStatus(nativeGitLab, gitUrl, after, "failed",
                        "CodeGuardian AI 审查失败: " + reason,
                        buildReportUrl(finalTask.getTaskId()));
                return;
            }

            String summary = String.format("严重 %d / 高危 %d / 中危 %d / 低危 %d",
                    finalTask.getCriticalCount(), finalTask.getHighCount(),
                    finalTask.getMediumCount(), finalTask.getLowCount());
            boolean passed = qualityGateService.checkQualityGate(finalTask.getTaskId(), "CRITICAL");

            updatePlatformStatus(nativeGitLab, gitUrl, after,
                    passed ? "success" : "failed",
                    "审查完成，问题：" + summary + (passed ? "（门禁通过）" : "（门禁未通过）"),
                    buildReportUrl(finalTask.getTaskId()));
        } catch (Exception e) {
            log.error("处理 GitLab Push 事件失败", e);
        }
    }

    /**
     * 回写 commit status：GitLab 优先使用 GitLab API，否则走 GitCode API
     */
    private void updatePlatformStatus(boolean nativeGitLab, String gitUrl, String sha,
                                      String state, String description) {
        updatePlatformStatus(nativeGitLab, gitUrl, sha, state, description, null);
    }

    private void updatePlatformStatus(boolean nativeGitLab, String gitUrl, String sha,
                                      String state, String description, String targetUrl) {
        if (nativeGitLab && gitLabFeedbackService.isConfigured()) {
            gitLabFeedbackService.updateCommitStatus(gitUrl, sha, state, description, targetUrl);
        } else {
            gitFeedbackService.updateStatus(gitUrl, sha, state, description);
        }
    }

    /**
     * 回写 MR 评论：GitLab 优先使用 GitLab API，否则走 GitCode API
     */
    private void postPlatformComment(boolean nativeGitLab, String gitUrl, int mrIid, String body) {
        if (mrIid <= 0) {
            return;
        }
        if (nativeGitLab && gitLabFeedbackService.isConfigured()) {
            gitLabFeedbackService.postMergeRequestNote(gitUrl, mrIid, body);
        } else {
            gitFeedbackService.postComment(gitUrl, String.valueOf(mrIid), body);
        }
    }

    private ReviewResponseDTO triggerReview(String gitUrl, String branch, String commit, String taskName,
                                            List<String> changedFiles) {
        ReviewRequestDTO request = ReviewRequestDTO.builder()
                .reviewType("GIT")
                .gitUrl(gitUrl)
                .gitBranch(branch)
                .gitCommit(commit)
                .taskName(taskName)
                .changedFiles(changedFiles != null && !changedFiles.isEmpty() ? changedFiles : null)
                .build();

        ReviewResponseDTO response = reviewService.createReviewTask(request);
        log.info("Webhook 触发审查: taskId={}, branch={}, commit={}, changedFiles={}",
                response.getTaskId(), branch, commit,
                changedFiles != null ? changedFiles.size() : 0);
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

    private void processGitHubPullRequest(String repoFullName, int prNumber, String gitUrl,
                                          String branch, String commit, String title) {
        try {
            if (githubFeedbackService.isConfigured()) {
                githubFeedbackService.updateCommitStatus(repoFullName, commit, "pending",
                        "CodeGuardian AI 审查中...", null);
            }

            List<String> changedFiles = githubFeedbackService.listPullRequestFiles(repoFullName, prNumber);
            ReviewResponseDTO response = triggerReview(gitUrl, branch, commit,
                    "PR-" + prNumber + "-" + title, changedFiles);

            if (githubFeedbackService.isConfigured()) {
                githubFeedbackService.postPullRequestComment(repoFullName, prNumber,
                        "🤖 **CodeGuardian AI** 已开始审查此 PR。\n\n任务 ID: `" + response.getTaskId() + "`");
            }

            ReviewResponseDTO finalTask = awaitTaskCompletion(response.getTaskId());
            if (finalTask == null) {
                githubFeedbackService.updateCommitStatus(repoFullName, commit, "error",
                        "CodeGuardian AI 审查超时", buildReportUrl(response.getTaskId()));
                return;
            }
            reportGitHubResult(repoFullName, prNumber, commit, finalTask);
        } catch (Exception e) {
            log.error("处理 GitHub PR 失败: {}#{}", repoFullName, prNumber, e);
            githubFeedbackService.updateCommitStatus(repoFullName, commit, "error",
                    "CodeGuardian AI 处理失败: " + e.getMessage(), null);
        }
    }

    private void processGitHubPush(String repoFullName, String gitUrl, String branch,
                                   String commit, String shortCommit, List<String> changedFiles) {
        try {
            if (githubFeedbackService.isConfigured()) {
                githubFeedbackService.updateCommitStatus(repoFullName, commit, "pending",
                        "CodeGuardian AI 审查中...", null);
            }

            ReviewResponseDTO response = triggerReview(gitUrl, branch, commit,
                    "PUSH-" + shortCommit, changedFiles);
            ReviewResponseDTO finalTask = awaitTaskCompletion(response.getTaskId());
            if (finalTask == null) {
                githubFeedbackService.updateCommitStatus(repoFullName, commit, "error",
                        "CodeGuardian AI 审查超时", buildReportUrl(response.getTaskId()));
                return;
            }
            reportGitHubResult(repoFullName, null, commit, finalTask);
        } catch (Exception e) {
            log.error("处理 GitHub Push 失败: {}@{}", repoFullName, shortCommit, e);
            githubFeedbackService.updateCommitStatus(repoFullName, commit, "error",
                    "CodeGuardian AI 处理失败: " + e.getMessage(), null);
        }
    }

    private void reportGitHubResult(String repoFullName, Integer prNumber,
                                    String commit, ReviewResponseDTO finalTask) {
        boolean failed = "FAILED".equals(finalTask.getStatus());
        boolean passed = !failed && qualityGateService.checkQualityGate(finalTask.getTaskId(), "CRITICAL");

        String summary = String.format("严重 %d / 高危 %d / 中危 %d / 低危 %d",
                finalTask.getCriticalCount(), finalTask.getHighCount(),
                finalTask.getMediumCount(), finalTask.getLowCount());

        String state = failed ? "failure" : (passed ? "success" : "failure");
        String description = failed
                ? "审查失败: " + (finalTask.getErrorMessage() != null ? finalTask.getErrorMessage() : "未知错误")
                : "审查完成，问题：" + summary;
        String targetUrl = buildReportUrl(finalTask.getTaskId());

        if (commit != null) {
            githubFeedbackService.updateCommitStatus(repoFullName, commit, state, description, targetUrl);
        }

        if (prNumber != null && prNumber > 0) {
            String body;
            if (failed) {
                body = "❌ **CodeGuardian AI** 审查失败。\n\n"
                        + "- 任务 ID: `" + finalTask.getTaskId() + "`\n"
                        + "- 原因: " + finalTask.getErrorMessage();
            } else {
                body = "🤖 **CodeGuardian AI** 审查完成。\n\n"
                        + "- 任务 ID: `" + finalTask.getTaskId() + "`\n"
                        + "- 问题统计: " + summary + "\n"
                        + "- 门禁（CRITICAL）: " + (passed ? "通过" : "未通过");
                if (targetUrl != null) {
                    body += "\n- 报告: " + targetUrl;
                }
                body += buildFindingsSection(finalTask.getTaskId());
            }
            githubFeedbackService.postPullRequestComment(repoFullName, prNumber, body);
        }
    }

    private ReviewResponseDTO awaitTaskCompletion(Long taskId) {
        for (int i = 0; i < 120; i++) {
            try {
                ReviewResponseDTO task = reviewService.getReviewTask(taskId);
                String status = task.getStatus();
                if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                    return task;
                }
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("等待审查任务 {} 被中断", taskId);
                return null;
            } catch (Exception e) {
                log.warn("轮询审查任务 {} 失败: {}", taskId, e.getMessage());
                return null;
            }
        }
        log.warn("等待审查任务 {} 超时", taskId);
        return null;
    }

    private String buildReportUrl(Long taskId) {
        if (!StringUtils.hasText(publicBaseUrl)) {
            return null;
        }
        return publicBaseUrl.replaceAll("/+$", "") + "/report/" + taskId;
    }

    /**
     * 生成评论中的主要问题列表（最多 5 条，按严重程度排序）
     */
    private String buildFindingsSection(Long taskId) {
        if (taskId == null) {
            return "";
        }
        List<Finding> findings;
        try {
            findings = findingRepository.findByTaskId(taskId);
        } catch (Exception e) {
            log.warn("查询任务 {} 的问题列表失败: {}", taskId, e.getMessage());
            return "";
        }
        if (findings == null || findings.isEmpty()) {
            return "";
        }

        List<Finding> top = findings.stream()
                .sorted(Comparator.comparingInt(f ->
                        f.getSeverity() != null ? f.getSeverity() : Integer.MAX_VALUE))
                .limit(5)
                .toList();

        StringBuilder section = new StringBuilder("\n\n**主要问题**\n");
        for (Finding finding : top) {
            String severity = finding.getSeverity() != null
                    ? SeverityEnum.fromValue(finding.getSeverity()).getDesc()
                    : "未知";
            section.append("- [").append(severity).append("] ")
                    .append(nullToEmpty(finding.getTitle()));
            String location = truncate(finding.getLocation(), 120);
            if (StringUtils.hasText(location)) {
                section.append(" · `").append(location).append("`");
            }
            section.append("\n");
        }
        if (findings.size() > top.size()) {
            section.append("- ... 其余 ").append(findings.size() - top.size()).append(" 个问题见报告\n");
        }
        return section.toString();
    }

    private String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 3) + "...";
    }

    /**
     * 获取 MR 变更文件。仅 GitLab 原生 API 可用，GitCode 回退全量审查。
     */
    private List<String> changedFilesForGitLabMr(boolean nativeGitLab, String gitUrl, int mrIid) {
        if (nativeGitLab && gitLabFeedbackService.isConfigured()) {
            return gitLabFeedbackService.listMergeRequestChangedFiles(gitUrl, mrIid);
        }
        return List.of();
    }

    /**
     * GitHub Push 事件的 commits[].added/modified 汇总为增量审查文件列表
     */
    private List<String> collectGitHubPushChangedFiles(JsonNode payload) {
        Set<String> paths = new LinkedHashSet<>();
        JsonNode commits = payload.path("commits");
        if (commits.isArray()) {
            for (JsonNode commit : commits) {
                addChangedPaths(paths, commit.path("added"));
                addChangedPaths(paths, commit.path("modified"));
            }
        }
        return new ArrayList<>(paths);
    }

    private void addChangedPaths(Set<String> paths, JsonNode array) {
        if (!array.isArray()) {
            return;
        }
        for (JsonNode node : array) {
            String path = node.asText(null);
            if (StringUtils.hasText(path)) {
                paths.add(path);
            }
        }
    }

    /**
     * GitLab / GitCode Push 事件的 commits[].added/modified 汇总
     */
    private List<String> collectPushChangedFiles(Map<String, Object> payload) {
        Set<String> paths = new LinkedHashSet<>();
        Object commits = payload.get("commits");
        if (commits instanceof List<?> commitList) {
            for (Object item : commitList) {
                if (item instanceof Map<?, ?> commit) {
                    addChangedPaths(paths, commit.get("added"));
                    addChangedPaths(paths, commit.get("modified"));
                }
            }
        }
        return new ArrayList<>(paths);
    }

    private void addChangedPaths(Set<String> paths, Object value) {
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String path && StringUtils.hasText(path)) {
                    paths.add(path);
                }
            }
        }
    }

    /**
     * 轮询任务状态，完成后回写评论与提交状态
     */
    private void awaitAndReportResult(String gitUrl, String commitSha, Integer mrIid, Long taskId,
                                      boolean nativeGitLab) {
        ReviewResponseDTO finalTask = awaitTaskCompletion(taskId);
        if (finalTask == null) {
            return;
        }

        if ("FAILED".equals(finalTask.getStatus())) {
            String reason = finalTask.getErrorMessage() != null
                    ? finalTask.getErrorMessage() : "未知错误";
            if (commitSha != null) {
                updatePlatformStatus(nativeGitLab, gitUrl, commitSha, "failed",
                        "CodeGuardian AI 审查失败: " + reason, buildReportUrl(taskId));
            }
            postPlatformComment(nativeGitLab, gitUrl, mrIid,
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
            updatePlatformStatus(nativeGitLab, gitUrl, commitSha,
                    passed ? "success" : "failed",
                    "CodeGuardian AI 审查完成，问题：" + summary,
                    buildReportUrl(taskId));
        }

        postPlatformComment(nativeGitLab, gitUrl, mrIid,
                "🤖 **CodeGuardian AI** 审查完成。\n\n" +
                "- 任务 ID: `" + taskId + "`\n" +
                "- 问题统计: " + summary + "\n" +
                "- 门禁（CRITICAL）: " + (passed ? "通过" : "未通过") + "\n" +
                "- 报告: `/report/" + taskId + "`" + buildFindingsSection(taskId));
    }
}
