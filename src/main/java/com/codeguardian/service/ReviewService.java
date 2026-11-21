package com.codeguardian.service;

import com.codeguardian.dto.FileContentDTO;
import com.codeguardian.dto.ReviewRequestDTO;
import com.codeguardian.dto.ReviewResponseDTO;
import com.codeguardian.entity.Finding;
import com.codeguardian.entity.ReviewTask;
import com.codeguardian.enums.ModelProviderEnum;
import com.codeguardian.repository.FindingRepository;
import com.codeguardian.repository.ReviewReportRepository;
import com.codeguardian.repository.ReviewTaskRepository;
import com.codeguardian.service.cache.SemanticFingerprintCacheService;
import com.codeguardian.service.notification.ReviewNotificationService;
import com.codeguardian.service.rules.RuleEngineService;
import com.codeguardian.enums.ReviewTypeEnum;
import com.codeguardian.enums.SeverityEnum;
import com.codeguardian.enums.TaskStatusEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 代码审查服务
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewService {
    
    private final ReviewTaskRepository taskRepository;
    private final FindingRepository findingRepository;
    private final ReviewReportRepository reportRepository;
    private final AIModelService aiModelService;
    private final CodeParserService codeParserService;
    private final RuleEngineService ruleEngineService;
    private final SystemConfigService configService;
    private final GitService gitService;
    private final SemanticFingerprintCacheService fingerprintCacheService;
    private final ReviewNotificationService notificationService;
    
    private final ExecutorService executor = Executors.newFixedThreadPool(20);
    private final ExecutorService orchestrationExecutor = Executors.newCachedThreadPool();
    
    @jakarta.annotation.PreDestroy
    public void destroy() {
        log.info("Closing review service executors...");
        executor.shutdown();
        orchestrationExecutor.shutdown();
    }
    
    /**
     * 创建审查任务并开始审查
     */
    @Transactional
    public ReviewResponseDTO createReviewTask(ReviewRequestDTO request) {
        log.info("创建审查任务: type={}, scope={}", request.getReviewType(), 
                request.getProjectPath() != null ? request.getProjectPath() : 
                request.getFilePath() != null ? request.getFilePath() : "代码片段");
        
        // 创建任务
        ReviewTask task = ReviewTask.builder()
                .name(request.getTaskName() != null ? request.getTaskName() : 
                      generateTaskName(request))
                .reviewType(ReviewTypeEnum.fromName(request.getReviewType()).getValue())
                .scope(determineScope(request))
                .status(TaskStatusEnum.RUNNING.getValue())
                .createdAt(LocalDateTime.now())
                .build();
        
        task = taskRepository.save(task);
        try {
            String type = request.getReviewType() != null ? request.getReviewType().toUpperCase() : "";
            if ("SNIPPET".equals(type)) {
                if (request.getCodeSnippet() != null && !request.getCodeSnippet().isBlank()) {
                    task.setScope(request.getCodeSnippet());
                    task = taskRepository.save(task);
                }
            } else if ("FILE".equals(type)) {
                if (request.getFiles() != null && !request.getFiles().isEmpty()) {
                    FileContentDTO f = request.getFiles().get(0);
                    if (f.getContent() != null) {
                        java.nio.file.Path root = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "codeguardian", "uploads", String.valueOf(task.getId()));
                        String rel = f.getPath() != null ? f.getPath().replace('\\','/') : "uploaded.txt";
                        java.nio.file.Path out = root.resolve(rel);
                        java.nio.file.Files.createDirectories(out.getParent());
                        java.nio.file.Files.writeString(out, f.getContent());
                        task.setScope(out.toString());
                        task = taskRepository.save(task);
                    }
                } else if (request.getFilePath() != null && !request.getFilePath().isBlank()) {
                    task.setScope(request.getFilePath());
                    task = taskRepository.save(task);
                }
            } else if ("GIT".equals(type)) {
                if (request.getGitUrl() != null && !request.getGitUrl().isBlank()) {
                    task.setScope(request.getGitUrl());
                    task = taskRepository.save(task);
                }
            }
        } catch (Exception e) {
            log.warn("保存代码样本失败，将使用默认范围标签", e);
        }
        
        ReviewTask finalTask = task;
        Long taskId = task.getId();
        Runnable reviewJob = () -> runReviewAsync(taskId, finalTask, request);

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    orchestrationExecutor.submit(reviewJob);
                }
            });
        } else {
            orchestrationExecutor.submit(reviewJob);
        }
        
        return buildResponseDTO(finalTask);
    }
    
    private void runReviewAsync(Long taskId, ReviewTask fallbackTask, ReviewRequestDTO request) {
        if (taskId == null) {
            log.error("审查任务执行失败: taskId 为空");
            return;
        }

        ReviewTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            task = fallbackTask;
        }
        if (task == null || task.getId() == null || !taskId.equals(task.getId())) {
            log.error("审查任务执行失败: taskId={} 不存在", taskId);
            return;
        }

        try {
            performReview(task, request);
            task.setStatus(TaskStatusEnum.COMPLETED.getValue());
            task.setCompletedAt(LocalDateTime.now());
        } catch (Exception e) {
            log.error("审查任务执行失败: taskId={}", task.getId(), e);
            task.setStatus(TaskStatusEnum.FAILED.getValue());
            task.setErrorMessage(e.getMessage());
        } finally {
            taskRepository.save(task);
            if (notificationService != null) {
                notificationService.notifyAsync(task);
            }
        }
    }

    /**
     * 执行审查
     */
    private void performReview(ReviewTask task, ReviewRequestDTO request) {
        String type = request.getReviewType().toUpperCase();
        
        if ("DIRECTORY".equals(type) || "PROJECT".equals(type)) {
            performParallelReview(task, request);
        } else if ("GIT".equals(type)) {
            if (request.getProjectPath() == null && request.getGitUrl() != null) {
                try {
                    log.info("Git项目尚未克隆，开始克隆: {}", request.getGitUrl());
                    String localPath = gitService.cloneRepository(
                            request.getGitUrl(),
                            request.getGitUsername(),
                            request.getGitPassword(),
                            request.getGitBranch(),
                            request.getGitCommit());
                    request.setProjectPath(localPath);
                    task.setScope(localPath);
                    taskRepository.save(task); // 更新任务范围
                    log.info("Git克隆完成，本地路径: {}", localPath);
                } catch (Exception e) {
                    log.error("Git自动克隆失败", e);
                    throw new RuntimeException("Git克隆失败: " + e.getMessage());
                }
            }
            
            if (request.getProjectPath() != null) {
                performParallelReview(task, request);
            } else {
                throw new UnsupportedOperationException("Git项目未克隆或路径为空");
            }
        } else {
            String codeContent = fetchCodeContent(request);
            if (codeContent == null || codeContent.trim().isEmpty()) {
                throw new IllegalArgumentException("代码内容为空");
            }
            List<Finding> findings = executeReviewStrategy(codeContent, request.getLanguage(), request);
            saveFindings(task, findings);
            log.info("审查完成: taskId={}, findingsCount={}", task.getId(), findings.size());
        }
    }

    /**
     * 使用线程池并行执行审查
     */
    private void performParallelReview(ReviewTask task, ReviewRequestDTO request) {
        List<Future<List<Finding>>> futures;

        if (request.getFiles() != null && !request.getFiles().isEmpty()) {
            // 来源A：直接上传的文件列表
            log.info("使用上传的文件列表进行审查: taskId={}, count={}", task.getId(), request.getFiles().size());
            futures = request.getFiles().stream()
                .map(file -> executor.submit(() -> reviewSingleFile(file.getPath(), file.getContent(), request)))
                .collect(Collectors.toList());
        } else {
            // 来源B：扫描本地目录
            String path = request.getProjectPath();
            if (path == null || path.trim().isEmpty()) {
                path = request.getDirectoryPath();
            }
            
            if (path == null || path.trim().isEmpty()) {
                 throw new IllegalArgumentException("项目或目录路径不能为空");
            }
    
            // 获取配置的范围
            String includePaths = configService.getSettings().getIncludePaths();
            String excludePaths = configService.getSettings().getExcludePaths();

            List<Path> files = codeParserService.scanDirectory(path, includePaths, excludePaths);
            final Path rootPath = Paths.get(path).toAbsolutePath().normalize();
            files = filterByChangedFiles(files, rootPath, request.getChangedFiles());
            log.info("开始并行审查本地目录: taskId={}, path={}, 文件数={}, 增量过滤={}",
                    task.getId(), path, files.size(),
                    request.getChangedFiles() != null && !request.getChangedFiles().isEmpty());

            futures = files.stream()
                .map(filePath -> executor.submit(() -> {
                    try {
                        String content = codeParserService.readFile(filePath.toString());
                        String relativePath = rootPath.relativize(filePath.toAbsolutePath().normalize()).toString();
                        return reviewSingleFile(relativePath, content, request);
                    } catch (Exception e) {
                        log.error("读取文件失败: {}", filePath, e);
                        throw new RuntimeException("读取文件失败: " + filePath + " - " + e.getMessage(), e);
                    }
                }))
                .collect(Collectors.toList());
        }

        List<Finding> allFindings = new ArrayList<>();
        List<String> reviewErrors = new ArrayList<>();
        for (Future<List<Finding>> future : futures) {
            try {
                List<Finding> findings = future.get();
                if (findings != null) {
                    allFindings.addAll(findings);
                }
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                String reason = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
                reviewErrors.add(reason);
                log.error("获取审查结果失败: {}", reason, cause);
            }
        }

        saveFindings(task, allFindings);
        log.info("并行审查完成: taskId={}, findingsCount={}, failedFiles={}", task.getId(), allFindings.size(), reviewErrors.size());
        if (!reviewErrors.isEmpty()) {
            String summary = reviewErrors.stream().distinct().limit(3).collect(Collectors.joining("; "));
            throw new RuntimeException("部分文件审查失败（" + reviewErrors.size() + " 个文件）: " + summary);
        }
    }

    /**
     * 增量审查：只保留变更文件列表中的文件。
     *
     * @param files        扫描出的文件
     * @param rootPath     扫描根目录（绝对路径）
     * @param changedFiles 相对仓库根路径的变更文件；为空时返回原列表
     */
    static List<Path> filterByChangedFiles(List<Path> files, Path rootPath, List<String> changedFiles) {
        if (changedFiles == null || changedFiles.isEmpty() || files == null || files.isEmpty()) {
            return files;
        }

        Set<String> changed = changedFiles.stream()
                .filter(Objects::nonNull)
                .map(ReviewService::normalizeRelativePath)
                .filter(path -> !path.isEmpty())
                .collect(Collectors.toSet());
        if (changed.isEmpty()) {
            return files;
        }

        return files.stream()
                .filter(file -> changed.contains(normalizeRelativePath(
                        rootPath.relativize(file.toAbsolutePath().normalize()).toString())))
                .collect(Collectors.toList());
    }

    static String normalizeRelativePath(String value) {
        if (value == null) {
            return "";
        }
        String path = value.trim().replace('\\', '/');
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        return path.replaceAll("^/+", "");
    }

    private List<Finding> reviewSingleFile(String relativePath, String content, ReviewRequestDTO request) {
        try {
            String language = detectLanguage(relativePath);
            List<Finding> fileFindings = executeReviewStrategy(content, language, request);
            
            fileFindings.forEach(f -> {
                f.setLocation(relativePath + ": " + f.getLocation());
            });
            
            return fileFindings;
        } catch (Exception e) {
            log.error("文件审查失败: {}", relativePath, e);
            throw new RuntimeException("文件审查失败 " + relativePath + ": " + e.getMessage(), e);
        }
    }
    
    private void saveFindings(ReviewTask task, List<Finding> findings) {
        findings.forEach(finding -> {
            finding.setTaskId(task.getId());
            findingRepository.save(finding);
        });
    }
    
    private String detectLanguage(String pathStr) {
        String fileName = Paths.get(pathStr).getFileName().toString().toLowerCase();
        if (fileName.endsWith(".java")) return "Java";
        if (fileName.endsWith(".py")) return "Python";
        if (fileName.endsWith(".js")) return "JavaScript";
        if (fileName.endsWith(".ts")) return "TypeScript";
        if (fileName.endsWith(".go")) return "Go";
        if (fileName.endsWith(".rs")) return "Rust";
        if (fileName.endsWith(".cpp") || fileName.endsWith(".c") || fileName.endsWith(".h")) return "C/C++";
        return "Unknown";
    }

    private String detectLanguage(Path path) {
        return detectLanguage(path.toString());
    }

    /**
     * 获取代码内容
     */
    private String fetchCodeContent(ReviewRequestDTO request) {
        return switch (request.getReviewType().toUpperCase()) {
            case "SNIPPET" -> request.getCodeSnippet();
            case "FILE" -> {
                if (request.getFiles() != null && !request.getFiles().isEmpty()) {
                    yield request.getFiles().get(0).getContent();
                }
                yield codeParserService.readFile(request.getFilePath());
            }
            default -> throw new IllegalArgumentException("不支持的审查类型: " + request.getReviewType());
        };
    }

    /**
     * 执行审查策略（规则引擎或AI模型）
     */
    private List<Finding> executeReviewStrategy(String codeContent, String language, ReviewRequestDTO request) {
        boolean useRulesOnly = Boolean.TRUE.equals(request.getRulesOnly());
        List<Finding> findings;
        
        if (useRulesOnly) {
            if ("CUSTOM".equalsIgnoreCase(request.getRuleTemplate())) {
                findings = ruleEngineService.reviewWithCustom(codeContent, request.getCustomRules());
            } else {
                findings = ruleEngineService.reviewWithTemplate(codeContent, language, request.getRuleTemplate());
            }
            // 规则引擎模式下手动标记来源
            if (findings != null) {
                findings.forEach(f -> f.setSource("RuleEngine"));
            }
        } else {
            boolean enableRag = request.getEnableRag() != null ? request.getEnableRag() : true;
            String requestedProvider = request.getModelProvider();
            ModelProviderEnum resolvedProvider = ModelProviderEnum.from(requestedProvider).orElse(null);
            if (resolvedProvider == null) {
                List<ModelProviderEnum> available = aiModelService.getAvailableProviders();
                if (available != null && !available.isEmpty()) {
                    resolvedProvider = available.get(0);
                }
            }

            final ModelProviderEnum provider = resolvedProvider;
            final String providerNameForAi = provider != null ? provider.name() : requestedProvider;
            final int blockStartLine = 1;
            final List<Long> knowledgeBaseIds = request.getKnowledgeBaseIds();
            // 指定了知识库范围时跳过语义缓存，避免命中其他知识库的结果
            final boolean canUseCache = knowledgeBaseIds == null || knowledgeBaseIds.isEmpty();
            findings = (canUseCache
                    ? fingerprintCacheService.tryGetCachedFindings(codeContent, language, provider, enableRag, blockStartLine)
                    : java.util.Optional.<List<Finding>>empty())
                    .orElseGet(() -> {
                        List<Finding> fresh = aiModelService.reviewCode(
                                codeContent,
                                language,
                                providerNameForAi,
                                enableRag,
                                null,
                                knowledgeBaseIds
                        );
                        if (canUseCache) {
                            fingerprintCacheService.storeFindings(codeContent, language, provider, enableRag, blockStartLine, fresh);
                        }
                        return fresh;
                    });
        }
        
        // 根据系统配置过滤、排序与截断问题
        try {
            com.codeguardian.model.dto.SettingsDTO settings = configService.getSettings();
            findings = new ArrayList<>(findings);

            Map<String, Boolean> categories = settings.getRuleCategories();
            if (categories != null && !categories.isEmpty()) {
                findings = findings.stream()
                        .filter(f -> isCategoryEnabled(f.getCategory(), categories))
                        .collect(Collectors.toList());
            }

            // 通用标准不展示低危问题；专业安全保留全部
            if ("general".equalsIgnoreCase(settings.getRulePreset())) {
                findings = findings.stream()
                        .filter(f -> !SeverityEnum.LOW.getValue().equals(f.getSeverity()))
                        .collect(Collectors.toList());
            }

            // 严重级别优先，其次按分类权重从高到低
            Map<String, Integer> weights = settings.getRuleWeights();
            findings.sort(java.util.Comparator
                    .comparingInt((Finding f) -> f.getSeverity() != null
                            ? f.getSeverity() : SeverityEnum.MEDIUM.getValue())
                    .thenComparingInt(f -> -categoryWeight(weights, f.getCategory())));

            int maxIssues = settings.getMaxIssues() != null ? settings.getMaxIssues() : 100;
            if (findings.size() > maxIssues) {
                findings = new ArrayList<>(findings.subList(0, maxIssues));
            }
        } catch (Exception e) {
            log.warn("应用系统设置过滤审查结果失败，返回原始结果", e);
        }

        return findings;
    }

    private int categoryWeight(Map<String, Integer> weights, String categoryCode) {
        if (weights == null) return 20;

        String key = "style";
        if ("SECURITY".equals(categoryCode)) key = "security";
        else if ("PERFORMANCE".equals(categoryCode)) key = "performance";
        else if ("BUG".equals(categoryCode)) key = "logic_error";
        else if ("MAINTAINABILITY".equals(categoryCode)) key = "maintainability";

        return weights.getOrDefault(key, 20);
    }

    private boolean isDuplicate(Finding newFinding, List<Finding> existingFindings) {
        if (existingFindings == null || existingFindings.isEmpty()) return false;
        for (Finding existing : existingFindings) {
            if (existing.getStartLine() == newFinding.getStartLine()) {
                if (existing.getCategory() != null && newFinding.getCategory() != null && 
                    existing.getCategory().equals(newFinding.getCategory())) {
                    return true;
                }
            }
        }
        return false;
    }
    
    private boolean isCategoryEnabled(String findingCategory, Map<String, Boolean> configCategories) {
        if (findingCategory == null) return true;
        String code = findingCategory;
        String key = "style";
        if ("SECURITY".equals(code)) key = "security";
        else if ("PERFORMANCE".equals(code)) key = "performance";
        else if ("BUG".equals(code)) key = "logic_error";
        else if ("MAINTAINABILITY".equals(code)) key = "maintainability";
        return configCategories.getOrDefault(key, true);
    }
    
    /**
     * 获取审查任务详情
     */
    public ReviewResponseDTO getReviewTask(Long taskId) {
        ReviewTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new RuntimeException("任务不存在: " + taskId));
        
        return buildResponseDTO(task);
    }
    
    /**
     * 构建响应DTO
     */
    private ReviewResponseDTO buildResponseDTO(ReviewTask task) {
        List<Finding> findings = findingRepository.findByTaskId(task.getId());
        if (findings == null) findings = List.of();
        
        // 使用Stream API一次性统计各级别问题数量
        Map<Integer, Integer> severityCounts = findings.stream()
                .filter(f -> f.getSeverity() != null)
                .collect(Collectors.groupingBy(Finding::getSeverity, Collectors.summingInt(e -> 1)));

        return ReviewResponseDTO.builder()
                .taskId(task.getId())
                .taskName(task.getName())
                .status(TaskStatusEnum.fromValue(task.getStatus()).name())
                .reviewType(ReviewTypeEnum.fromValue(task.getReviewType()).name())
                .scope(task.getScope())
                .createdAt(task.getCreatedAt())
                .errorMessage(task.getErrorMessage())
                .totalFindings(findings.size())
                .criticalCount(severityCounts.getOrDefault(SeverityEnum.CRITICAL.getValue(), 0))
                .highCount(severityCounts.getOrDefault(SeverityEnum.HIGH.getValue(), 0))
                .mediumCount(severityCounts.getOrDefault(SeverityEnum.MEDIUM.getValue(), 0))
                .lowCount(severityCounts.getOrDefault(SeverityEnum.LOW.getValue(), 0))
                .build();
    }
    
    /**
     * 生成任务名称
     *
     * <p>项目/目录/文件/Git 类型优先使用路径或仓库名，保证仪表盘展示清晰名称。</p>
     *
     * @param request 审查请求
     * @return 任务名称
     */
    private String generateTaskName(ReviewRequestDTO request) {
        String type = request.getReviewType() != null ? request.getReviewType().toUpperCase() : "";
        switch (type) {
            case "PROJECT": {
                String base = extractBaseName(request.getProjectPath());
                if (base != null && !base.isEmpty()) return base;
                break;
            }
            case "DIRECTORY": {
                String dirPath = request.getDirectoryPath();
                if (dirPath != null && !dirPath.isBlank()) {
                    String normalized = dirPath.replace('\\', '/');
                    if (normalized.contains("/")) return normalized;
                }
                if (request.getFiles() != null && !request.getFiles().isEmpty()) {
                    List<String> dirs = request.getFiles().stream()
                            .map(f -> f.getPath())
                            .filter(p -> p != null && !p.isBlank())
                            .map(p -> p.replace('\\', '/'))
                            .map(p -> {
                                int last = p.lastIndexOf('/');
                                return last >= 0 ? p.substring(0, last) : "";
                            })
                            .collect(Collectors.toList());
                    String common = computeCommonDir(dirs);
                    if (common != null && !common.isEmpty()) return common;
                }
                if (dirPath != null && !dirPath.isBlank()) return dirPath;
                break;
            }
            case "FILE": {
                String base = extractBaseName(request.getFilePath());
                if (base != null && !base.isEmpty()) return base;
                break;
            }
            case "SNIPPET": {
                String identifier = guessSnippetDisplayName(request.getCodeSnippet(), request.getLanguage());
                if (identifier != null && !identifier.isEmpty()) {
                    return identifier;
                }
                break;
            }
            case "GIT": {
                if (request.getProjectPath() != null && !request.getProjectPath().trim().isEmpty()) {
                    String base = extractBaseName(request.getProjectPath());
                    if (base != null && !base.isEmpty()) return base;
                }
                String repo = extractRepoNameFromUrl(request.getGitUrl());
                if (repo != null && !repo.isEmpty()) return repo;
                if (request.getGitUrl() != null && !request.getGitUrl().isBlank()) {
                    return request.getGitUrl();
                }
                break;
            }
            default:
                break;
        }

        String prefix = switch (type) {
            case "PROJECT" -> "项目审查";
            case "DIRECTORY" -> "目录审查";
            case "FILE" -> "文件审查";
            case "SNIPPET" -> "代码片段审查";
            case "GIT" -> "Git项目审查";
            default -> "代码审查";
        };
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String computeCommonDir(List<String> paths) {
        if (paths == null || paths.isEmpty()) return null;
        List<String[]> segments = paths.stream()
                .map(p -> p.split("/"))
                .collect(Collectors.toList());
        int minLen = segments.stream().mapToInt(a -> a.length).min().orElse(0);
        if (minLen == 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < minLen; i++) {
            String seg = segments.get(0)[i];
            boolean allSame = true;
            for (int j = 1; j < segments.size(); j++) {
                if (!seg.equals(segments.get(j)[i])) { allSame = false; break; }
            }
            if (!allSame) break;
            if (sb.length() > 0) sb.append('/');
            sb.append(seg);
        }
        return sb.toString();
    }

    /**
     * 从路径中提取末级名称
     *
     * @param path 路径
     * @return 末级名称（目录名或文件名），路径为空时返回 null
     */
    private String extractBaseName(String path) {
        if (path == null || path.trim().isEmpty()) return null;
        try {
            return Paths.get(path).getFileName().toString();
        } catch (Exception e) {
            return null;
        }
    }

    public static String guessSnippetDisplayName(String code, String language) {
        if (code == null || code.trim().isEmpty()) return null;
        String lang = language != null ? language.toLowerCase() : "";
        if (lang.contains("java")) {
            Pattern classPat = Pattern.compile("\\b(class|interface|enum)\\s+([A-Za-z_][A-Za-z0-9_]*)");
            Matcher cm = classPat.matcher(code);
            String className = cm.find() ? cm.group(2) : null;
            Pattern methodPat = Pattern.compile("\\b(?:public|protected|private|static|final|synchronized|abstract|native|transient|\\s)+\\s*[\\w<>\\[\\]]+\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
            Matcher mm = methodPat.matcher(code);
            String methodName = mm.find() ? mm.group(1) : null;
            if (methodName != null) return className != null ? className + "." + methodName : methodName;
            if (className != null) return className;
            return null;
        } else if (lang.contains("python")) {
            Pattern classPat = Pattern.compile("\\bclass\\s+([A-Za-z_][A-Za-z0-9_]*)");
            Matcher cm = classPat.matcher(code);
            String className = cm.find() ? cm.group(1) : null;
            Pattern methodPat = Pattern.compile("\\bdef\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
            Matcher mm = methodPat.matcher(code);
            String methodName = mm.find() ? mm.group(1) : null;
            if (methodName != null) return className != null ? className + "." + methodName : methodName;
            if (className != null) return className;
            return null;
        } else if (lang.contains("typescript") || lang.contains("ts") || lang.contains("javascript") || lang.contains("js")) {
            Pattern classPat = Pattern.compile("\\bclass\\s+([A-Za-z_][A-Za-z0-9_]*)");
            Matcher cm = classPat.matcher(code);
            String className = cm.find() ? cm.group(1) : null;
            Pattern funcPat1 = Pattern.compile("\\bfunction\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
            Matcher m1 = funcPat1.matcher(code);
            String methodName = m1.find() ? m1.group(1) : null;
            if (methodName == null) {
                Pattern funcPat2 = Pattern.compile("\\bconst\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*\\(");
                Matcher m2 = funcPat2.matcher(code);
                methodName = m2.find() ? m2.group(1) : null;
            }
            if (methodName != null) return className != null ? className + "." + methodName : methodName;
            if (className != null) return className;
            return null;
        }
        Pattern genericClass = Pattern.compile("\\b(class|interface|enum)\\s+([A-Za-z_][A-Za-z0-9_]*)");
        Matcher gc = genericClass.matcher(code);
        if (gc.find()) return gc.group(2);
        Pattern genericFunc = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
        Matcher gf = genericFunc.matcher(code);
        return gf.find() ? gf.group(1) : null;
    }

    /**
     * 从 Git 仓库URL中解析仓库名
     *
     * @param url 仓库URL
     * @return 仓库名（去掉 .git 后缀），解析失败返回 null
     */
    private String extractRepoNameFromUrl(String url) {
        if (url == null || url.trim().isEmpty()) return null;
        try {
            String trimmed = url.trim();
            if (trimmed.endsWith(".git")) trimmed = trimmed.substring(0, trimmed.length() - 4);
            int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
            if (slash >= 0 && slash < trimmed.length() - 1) {
                return trimmed.substring(slash + 1);
            }
            return trimmed;
        } catch (Exception e) {
            return null;
        }
    }
    
    private String determineScope(ReviewRequestDTO request) {
        String type = request.getReviewType() != null ? request.getReviewType().toUpperCase() : "UNKNOWN";
        switch (type) {
            case "PROJECT":
                return "整个项目";
            case "DIRECTORY":
                return "指定目录";
            case "FILE":
                return "指定文件";
            case "GIT":
                return "git项目";
            case "SNIPPET":
                return "代码片段";
            default:
                return "代码片段";
        }
    }

    /**
     * 删除审查任务及其关联数据
     * 按外键依赖顺序删除：findings -> review_reports -> review_task
     *
     * @param taskId 任务ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteTaskWithRelatedData(Long taskId) {
        log.info("开始删除任务及其关联数据: taskId={}", taskId);

        // 1. 删除审查发现
        long findingsCount = findingRepository.countByTaskId(taskId);
        if (findingsCount > 0) {
            findingRepository.deleteByTaskId(taskId);
            log.info("已删除 {} 条审查发现: taskId={}", findingsCount, taskId);
        }

        // 2. 删除审查报告
        reportRepository.findByTaskId(taskId).ifPresent(report -> {
            reportRepository.delete(report);
            log.info("已删除审查报告: taskId={}", taskId);
        });

        // 3. 删除任务
        taskRepository.findById(taskId).ifPresent(task -> {
            taskRepository.delete(task);
            log.info("已删除任务: taskId={}, taskName={}", taskId, task.getName());
        });

        log.info("任务及其关联数据删除完成: taskId={}", taskId);
    }
}
