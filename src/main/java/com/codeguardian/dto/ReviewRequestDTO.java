package com.codeguardian.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 代码审查请求DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewRequestDTO {
    
    /**
     * 审查类型：PROJECT, DIRECTORY, FILE, SNIPPET, GIT
     * 由各 Controller 端点自动设置，客户端无需传
     */
    private String reviewType;
    
    /**
     * 项目路径（当type为PROJECT或DIRECTORY时使用）
     */
    private String projectPath;
    
    /**
     * 目录路径（当type为DIRECTORY时使用）
     */
    private String directoryPath;
    
    /**
     * 文件路径（当type为FILE时使用）
     */
    private String filePath;
    
    /**
     * 代码片段（当type为SNIPPET时使用）
     */
    private String codeSnippet;
    
    /**
     * 代码语言（当type为SNIPPET时使用）
     */
    private String language;
    
    /**
     * Git仓库地址（当type为GIT时使用）
     */
    private String gitUrl;
    
    /**
     * Git用户名（可选）
     */
    private String gitUsername;
    
    /**
     * Git密码/令牌（可选，仅会话态）
     */
    private String gitPassword;

    /**
     * Git 分支（GIT 类型可选）
     */
    private String gitBranch;

    /**
     * Git 提交哈希（GIT 类型可选）
     */
    private String gitCommit;
    
    /**
     * 任务名称（可选，默认自动生成）
     */
    private String taskName;
    
    /**
     * AI模型提供商（可选，默认使用配置的provider）
     * 可选值：OPENAI, QWEN, DEEPSEEK
     */
    private String modelProvider;

    /**
     * 是否仅使用规范规则进行审查（不调用大模型）
     */
    private Boolean rulesOnly;

    /**
     * 规范模板：ALIBABA, GOOGLE, AIRBNB, PEP8, CUSTOM
     */
    private String ruleTemplate;

    /**
     * 自定义规范规则（仅当ruleTemplate=CUSTOM时可用）
     */
    private java.util.List<CustomRuleDTO> customRules;
    
    /**
     * 上传的文件列表（当type为DIRECTORY或PROJECT且直接上传文件内容时使用）
     */
    private java.util.List<FileContentDTO> files;

    /**
     * 是否启用RAG知识库增强
     */
    private Boolean enableRag;

    /**
     * 知识库范围（可选，空表示全部知识库）
     */
    private java.util.List<Long> knowledgeBaseIds;

    /**
     * 增量审查范围：仅审查这些文件（相对仓库根路径）。
     * 为空时审查扫描范围内的全部文件，主要用于 PR/MR 与 Push 事件。
     */
    private java.util.List<String> changedFiles;
}
