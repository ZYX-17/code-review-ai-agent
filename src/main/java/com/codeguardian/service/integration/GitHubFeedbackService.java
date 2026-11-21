package com.codeguardian.service.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GitHub 反馈服务
 *
 * <p>使用 GitHub REST API 在 PR 上发表评论、更新 commit status。
 * 需要配置 github.api.token（或 GITHUB_TOKEN）。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GitHubFeedbackService {

    private final ObjectMapper objectMapper;

    @Value("${github.api.token:}")
    private String token;

    @Value("${github.api.base-url:https://api.github.com}")
    private String baseUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public boolean isConfigured() {
        return StringUtils.hasText(token);
    }

    /**
     * 在 PR 上发表评论
     *
     * @param repoFullName owner/repo
     * @param prNumber     PR 编号
     * @param body         评论内容
     */
    public void postPullRequestComment(String repoFullName, int prNumber, String body) {
        if (!isConfigured()) {
            log.info("GITHUB_TOKEN 未配置，跳过 PR 评论: {}#{}", repoFullName, prNumber);
            return;
        }

        String url = baseUrl + "/repos/" + repoFullName + "/issues/" + prNumber + "/comments";
        send("POST", url, Map.of("body", body));
    }

    /**
     * 更新 commit status
     *
     * @param repoFullName owner/repo
     * @param sha          提交 SHA
     * @param state        pending / success / failure / error
     * @param description  简短描述（GitHub 限制 140 字符）
     * @param targetUrl    详情链接，可为空
     */
    public void updateCommitStatus(String repoFullName, String sha, String state,
                                   String description, String targetUrl) {
        if (!isConfigured()) {
            log.info("GITHUB_TOKEN 未配置，跳过 commit status: {}@{}", repoFullName, sha);
            return;
        }

        String url = baseUrl + "/repos/" + repoFullName + "/statuses/" + sha;
        Map<String, Object> payload = new HashMap<>();
        payload.put("state", state);
        payload.put("context", "codeguardian-ai");
        payload.put("description", truncate(description, 140));
        if (StringUtils.hasText(targetUrl)) {
            payload.put("target_url", targetUrl);
        }

        send("POST", url, payload);
    }

    /**
     * 获取 PR 的变更文件列表（仓库根路径相对路径）
     *
     * <p>用于增量审查；最多拉取 1000 个文件。未配置 Token 或请求失败时返回空列表，
     * 调用方应回退到全量审查。</p>
     */
    public List<String> listPullRequestFiles(String repoFullName, int prNumber) {
        List<String> files = new ArrayList<>();
        if (!isConfigured()) {
            log.info("GITHUB_TOKEN 未配置，跳过获取 PR 变更文件: {}#{}", repoFullName, prNumber);
            return files;
        }

        try {
            for (int page = 1; page <= 10; page++) {
                String url = baseUrl + "/repos/" + repoFullName + "/pulls/" + prNumber
                        + "/files?per_page=100&page=" + page;
                String body = getJson(url);
                if (body == null) {
                    break;
                }
                JsonNode array = objectMapper.readTree(body);
                if (!array.isArray() || array.isEmpty()) {
                    break;
                }
                for (JsonNode node : array) {
                    String filename = node.path("filename").asText(null);
                    if (StringUtils.hasText(filename)) {
                        files.add(filename);
                    }
                }
                if (array.size() < 100) {
                    break;
                }
            }
            log.info("获取 PR {}#{} 变更文件 {} 个", repoFullName, prNumber, files.size());
        } catch (Exception e) {
            log.warn("获取 PR 变更文件失败: {}#{}: {}", repoFullName, prNumber, e.getMessage());
        }
        return files;
    }

    private void send(String method, String url, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("User-Agent", "CodeGuardian-AI")
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("GitHub API 调用成功: {} {} -> {}", method, url, response.statusCode());
            } else {
                log.warn("GitHub API 调用失败: {} {} -> {} {}", method, url,
                        response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.warn("GitHub API 调用异常: {} {}: {}", method, url, e.getMessage());
        }
    }

    private String getJson(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("User-Agent", "CodeGuardian-AI")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            }
            log.warn("GitHub API 调用失败: GET {} -> {} {}", url,
                    response.statusCode(), response.body());
        } catch (Exception e) {
            log.warn("GitHub API 调用异常: GET {}: {}", url, e.getMessage());
        }
        return null;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 3) + "...";
    }
}
