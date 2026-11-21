package com.codeguardian.dto.integration;

import lombok.Data;

import java.util.List;

@Data
public class CicdTriggerRequest {
    private String gitUrl;
    private String branch;
    private String commitHash;
    private String triggerBy; // e.g., "JENKINS", "GITLAB_CI"
    private String projectPath; // 可选，指定子目录
    private String blockOn; // 阻断级别
    private Boolean rulesOnly; // 可选，仅规则审查
    private String ruleTemplate; // 可选，规则模板
    /**
     * 可选，增量审查文件列表（相对仓库根路径）。
     * CI 可传 `git diff --name-only origin/main...HEAD` 的结果。
     */
    private List<String> changedFiles;
}
