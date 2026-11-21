package com.codeguardian.dto.integration;

import lombok.Data;

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
}
