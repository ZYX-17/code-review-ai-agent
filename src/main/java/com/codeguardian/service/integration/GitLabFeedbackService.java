package com.codeguardian.service.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GitLab 反馈服务
 *
 * <p>使用 GitLab REST API v4 在 MR 上评论、更新 commit status。
 * 需要配置 gitlab.api.token（或 GITLAB_TOKEN）；自建 GitLab 通过
 * gitlab.api.base-url（或 GITLAB_API_URL）指定，例如
 * {@code https://gitlab.example.com/api/v4}。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GitLabFeedbackService {

    private final ObjectMapper objectMapper;

    @Value("${gitlab.api.token:}")
    private String token;

    @Value("${gitlab.api.base-url:https://gitlab.com/api/v4}")
    private String baseUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public boolean isConfigured() {
        return StringUtils.hasText(token);
    }

    /**
     * 在 Merge Request 上发表评论
     *
     * @param gitUrl 仓库 clone 地址（https）
     * @param mrIid  MR 的 iid（项目内编号）
     * @param body   评论内容
     */
    public void postMergeRequestNote(String gitUrl, int mrIid, String body) {
        if (!isConfigured()) {
            log.info("GITLAB_TOKEN 未配置，跳过 MR 评论: {}!{}", gitUrl, mrIid);
            return;
        }

        String project = encodeProjectPath(extractProjectPath(gitUrl));
        if (project == null) {
            log.warn("无法从 Git 地址解析项目路径，跳过 MR 评论: {}", gitUrl);
            return;
        }

        String url = trimTrailingSlash(baseUrl) + "/projects/" + project
                + "/merge_requests/" + mrIid + "/notes";
        send("POST", url, Map.of("body", body));
    }

    /**
     * 更新 commit status
     *
     * @param gitUrl      仓库 clone 地址（https）
     * @param sha         提交 SHA
     * @param state       pending / success / failure / error
     * @param description 简短描述
     * @param targetUrl   详情链接，可为空
     */
    public void updateCommitStatus(String gitUrl, String sha, String state,
                                   String description, String targetUrl) {
        if (!isConfigured()) {
            log.info("GITLAB_TOKEN 未配置，跳过 commit status: {}@{}", gitUrl, sha);
            return;
        }

        String project = encodeProjectPath(extractProjectPath(gitUrl));
        if (project == null) {
            log.warn("无法从 Git 地址解析项目路径，跳过 commit status: {}", gitUrl);
            return;
        }

        String url = trimTrailingSlash(baseUrl) + "/projects/" + project + "/statuses/" + sha;
        Map<String, Object> payload = new HashMap<>();
        payload.put("state", mapState(state));
        payload.put("name", "codeguardian-ai");
        payload.put("description", truncate(description, 255));
        if (StringUtils.hasText(targetUrl)) {
            payload.put("target_url", targetUrl);
        }

        send("POST", url, payload);
    }

    /**
     * 获取 MR 的变更文件列表（仓库根路径相对路径）
     *
     * <p>用于增量审查；未配置 Token 或请求失败时返回空列表，
     * 调用方应回退到全量审查。</p>
     */
    public List<String> listMergeRequestChangedFiles(String gitUrl, int mrIid) {
        List<String> files = new ArrayList<>();
        if (!isConfigured()) {
            log.info("GITLAB_TOKEN 未配置，跳过获取 MR 变更文件: {}!{}", gitUrl, mrIid);
            return files;
        }

        String project = encodeProjectPath(extractProjectPath(gitUrl));
        if (project == null) {
            return files;
        }

        try {
            for (int page = 1; page <= 10; page++) {
                String url = trimTrailingSlash(baseUrl) + "/projects/" + project
                        + "/merge_requests/" + mrIid + "/diffs?per_page=100&page=" + page;
                String body = getJson(url);
                if (body == null) {
                    break;
                }
                JsonNode array = objectMapper.readTree(body);
                if (!array.isArray() || array.isEmpty()) {
                    break;
                }
                for (JsonNode node : array) {
                    String path = node.path("new_path").asText(null);
                    if (!StringUtils.hasText(path)) {
                        path = node.path("old_path").asText(null);
                    }
                    if (StringUtils.hasText(path)) {
                        files.add(path);
                    }
                }
                if (array.size() < 100) {
                    break;
                }
            }
            log.info("获取 MR {}!{} 变更文件 {} 个", gitUrl, mrIid, files.size());
        } catch (Exception e) {
            log.warn("获取 MR 变更文件失败: {}!{}: {}", gitUrl, mrIid, e.getMessage());
        }
        return files;
    }

    /**
     * 从 clone 地址解析 GitLab 项目路径（owner/repo）
     *
     * <p>支持 https://host/group/sub/repo.git 与 git@host:group/repo.git。</p>
     */
    static String extractProjectPath(String gitUrl) {
        if (!StringUtils.hasText(gitUrl)) {
            return null;
        }

        String value = gitUrl.trim();
        String path;
        if (value.startsWith("git@")) {
            int colon = value.indexOf(':');
            if (colon < 0) {
                return null;
            }
            path = value.substring(colon + 1);
        } else {
            int schemeEnd = value.indexOf("://");
            if (schemeEnd < 0) {
                return null;
            }
            int pathStart = value.indexOf('/', schemeEnd + 3);
            if (pathStart < 0) {
                return null;
            }
            path = value.substring(pathStart + 1);
        }

        if (path.endsWith(".git")) {
            path = path.substring(0, path.length() - 4);
        }
        path = path.replaceAll("^/+", "").replaceAll("/+$", "");
        return StringUtils.hasText(path) ? path : null;
    }

    /**
     * GitLab 使用 %2F 编码项目路径
     */
    static String encodeProjectPath(String projectPath) {
        if (!StringUtils.hasText(projectPath)) {
            return null;
        }
        return URLEncoder.encode(projectPath, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * 将内部状态映射为 GitLab commit status
     */
    static String mapState(String state) {
        if (!StringUtils.hasText(state)) {
            return "pending";
        }
        return switch (state.toLowerCase()) {
            case "success", "passed" -> "success";
            case "failure", "failed", "error" -> "failed";
            case "canceled", "cancelled" -> "canceled";
            default -> "pending";
        };
    }

    private String trimTrailingSlash(String value) {
        return value != null ? value.replaceAll("/+$", "") : "";
    }

    private void send(String method, String url, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("PRIVATE-TOKEN", token)
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("GitLab API 调用成功: {} {} -> {}", method, url, response.statusCode());
            } else {
                log.warn("GitLab API 调用失败: {} {} -> {} {}", method, url,
                        response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.warn("GitLab API 调用异常: {} {}: {}", method, url, e.getMessage());
        }
    }

    private String getJson(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("PRIVATE-TOKEN", token)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            }
            log.warn("GitLab API 调用失败: GET {} -> {} {}", url,
                    response.statusCode(), response.body());
        } catch (Exception e) {
            log.warn("GitLab API 调用异常: GET {}: {}", url, e.getMessage());
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
