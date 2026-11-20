package com.codeguardian.service;

import com.codeguardian.config.AIConfigProperties;
import com.codeguardian.entity.Finding;
import com.codeguardian.enums.ModelProviderEnum;
import com.codeguardian.service.ai.factory.ChatClientFactory;
import com.codeguardian.service.rag.KnowledgeBaseService;
import com.codeguardian.service.ai.PromptService;
import com.codeguardian.service.ai.context.ReviewContextHolder;
import com.codeguardian.service.ai.output.CodeReviewOutputParser;
import com.codeguardian.service.ai.tool.ToolRegistry;
import org.springframework.ai.model.function.FunctionCallback;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * AI模型服务
 * 
 * <p>使用Spring AI的ChatClient和PromptTemplate进行代码审查</p>
 * 
 * @author 苏三
 * @since 1.0.0
 */
@Service
@Lazy
@RequiredArgsConstructor
@Slf4j
public class AIModelService {
    
    private final ChatClientFactory chatClientFactory;
    private final PromptService promptService;
    private final CodeReviewOutputParser outputParser;
    private final KnowledgeBaseService knowledgeBaseService;
    private final AIConfigProperties aiConfigProperties;
    private final ToolRegistry toolRegistry;
    
    /**
     * 审查代码
     * 
     * @param codeContent 代码内容
     * @param language 代码语言
     * @param modelProvider 模型提供商（可选，如果为null则使用配置的provider）
     * @param enableRag 是否启用RAG知识库增强
     * @return 审查发现的问题列表
     */
    public List<Finding> reviewCode(String codeContent, String language, String modelProvider, boolean enableRag) {
        return reviewCode(codeContent, language, modelProvider, enableRag, null);
    }

    /**
     * 审查代码（带已有发现）
     * 
     * @param codeContent 代码内容
     * @param language 代码语言
     * @param modelProvider 模型提供商
     * @param enableRag 是否启用RAG
     * @param existingFindings 已有的静态分析发现
     * @return 审查发现的问题列表
     */
    public List<Finding> reviewCode(String codeContent, String language, String modelProvider, boolean enableRag, List<Finding> existingFindings) {
        if (Boolean.FALSE.equals(aiConfigProperties.getEnabled())) {
            log.warn("LLM调用已禁用（ai.enabled=false），无法执行AI审查");
            throw new IllegalStateException("AI审查已禁用（ai.enabled=false）");
        }
        if (!chatClientFactory.hasAvailableProviders()) {
            log.warn("未配置任何可用的大模型，无法执行AI审查");
            throw new IllegalStateException("未配置可用的大模型，无法执行AI审查");
        }
        String finalProvider = modelProvider != null ? modelProvider : "默认";
        log.info("========== 开始AI代码审查 ==========");
        log.info("语言: {}, 代码长度: {} 字符, 模型提供商: {}, RAG增强: {}, 已有发现数: {}", 
                language, codeContent.length(), finalProvider, enableRag, existingFindings != null ? existingFindings.size() : 0);
        
        try {
            // 清理上下文中的旧数据
            ReviewContextHolder.clear();
            
            // RAG Retrieval (知识库增强)
            String context = null;
            if (enableRag) {
                context = retrieveContext(codeContent, language);
            }
            
            // 构建Prompt（使用Spring AI的PromptTemplate）
            long promptStartTime = System.currentTimeMillis();
            Prompt prompt = promptService.buildCodeReviewPrompt(codeContent, language, context, existingFindings);
            long promptBuildTime = System.currentTimeMillis() - promptStartTime;
            
            // 获取提示词内容
            String promptText = prompt.getContents();
            log.info("Prompt构建完成, 耗时: {}ms, Prompt长度: {} 字符",
                    promptBuildTime, promptText.length());
            
            // 打印完整的提示词内容
            log.info("========== 发送给大模型的提示词 ==========");
            log.info("{}", promptText);
            log.info("========== 提示词结束 ==========");
            
            // 获取ChatClient（使用Spring AI的ChatClient）
            ModelProviderEnum resolvedProvider = ModelProviderEnum.from(modelProvider).orElse(null);
            ChatClient chatClient = chatClientFactory.createChatClient(resolvedProvider);
            log.info("使用模型提供商: {}", resolvedProvider != null ? resolvedProvider.getCode() : "默认");
            
            // 获取所有注册的工具回调
            List<FunctionCallback> toolCallbacks = new ArrayList<>(toolRegistry.getFunctionCallbacks());
            
            // 如果已经有静态分析结果，暂时移除semgrepAnalysis工具，避免重复调用
            if (existingFindings != null && !existingFindings.isEmpty()) {
                toolCallbacks.removeIf(callback -> callback.getName().equalsIgnoreCase("semgrepAnalysis"));
                log.info("已传入静态分析结果，自动禁用 Semgrep 工具以避免重复执行");
            }
            
            if (!toolCallbacks.isEmpty()) {
                log.info("启用工具: {}", toolCallbacks.stream().map(FunctionCallback::getName).toList());
            } else {
                log.warn("未发现任何注册工具，将仅进行纯文本审查");
            }

            // 调用AI API（使用Spring AI的ChatClient）
            long apiStartTime = System.currentTimeMillis();
            log.info("[Step 1] 发送请求给大模型 (等待模型响应或Function Calling请求)...");
            
            String response = chatClient.prompt(prompt)
                    .functions(toolCallbacks.toArray(new FunctionCallback[0]))
                    .call()
                    .content();
            
            log.info("[Step Final] 大模型最终响应完成");
            long apiCallTime = System.currentTimeMillis() - apiStartTime;
            
            log.info("AI API调用成功: 响应时间={}ms, 响应内容长度={} 字符", 
                    apiCallTime, response != null ? response.length() : 0);
            
            // 打印AI返回的原始内容（用于调试）
            if (response != null) {
                log.info("========== AI返回的原始内容 ==========");
                log.info("{}", response);
                log.info("========== AI返回内容结束 ==========");
            } else {
                log.warn("AI返回的内容为空");
            }
            
            // 解析响应（使用Spring AI的OutputParser）
            long parseStartTime = System.currentTimeMillis();
            List<Finding> findings = outputParser.parse(response);
            long parseTime = System.currentTimeMillis() - parseStartTime;
            
            // 合并工具发现的问题 (例如Semgrep)
            List<Finding> toolFindings = ReviewContextHolder.getFindings();
            if (!toolFindings.isEmpty()) {
                log.info("从工具上下文合并 {} 个问题", toolFindings.size());
                // 简单去重：如果位置和标题完全相同，则认为是重复的
                for (Finding tf : toolFindings) {
                    boolean exists = findings.stream().anyMatch(f -> 
                        (f.getStartLine() != null && f.getStartLine().equals(tf.getStartLine())) && 
                        (f.getTitle() != null && f.getTitle().contains(tf.getTitle()))
                    );
                    
                    if (!exists) {
                        findings.add(tf);
                    }
                }
            }
            
            log.info("========== AI审查完成 ==========");
            log.info("提供商: {}, 发现问题数: {}, 总耗时: {}ms, 解析耗时: {}ms", 
                    finalProvider, 
                    findings.size(), 
                    System.currentTimeMillis() - promptStartTime,
                    parseTime);
            
            if (findings.isEmpty()) {
                log.warn("未发现任何问题，可能需要检查Prompt或模型响应");
            } else {
                // 统计问题严重程度
                long criticalCount = findings.stream().filter(f -> "CRITICAL".equals(f.getSeverity())).count();
                long highCount = findings.stream().filter(f -> "HIGH".equals(f.getSeverity())).count();
                long mediumCount = findings.stream().filter(f -> "MEDIUM".equals(f.getSeverity())).count();
                long lowCount = findings.stream().filter(f -> "LOW".equals(f.getSeverity())).count();
                log.info("问题统计: CRITICAL={}, HIGH={}, MEDIUM={}, LOW={}", 
                        criticalCount, highCount, mediumCount, lowCount);
            }
            
            return findings;
            
        } catch (Exception e) {
            log.error("========== AI审查异常 ==========");
            log.error("提供商: {}, 错误信息: {}", finalProvider, e.getMessage(), e);
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new RuntimeException("AI审查失败: " + reason, e);
        }
    }
    
    /**
     * 审查代码（使用配置的默认模型提供商）
     * 
     * @param codeContent 代码内容
     * @param language 代码语言
     * @return 审查发现的问题列表
     */
    public List<Finding> reviewCode(String codeContent, String language) {
        return reviewCode(codeContent, language, null, true);
    }
    
    /**
     * 获取可用的模型提供商列表
     * 
     * @return 提供商名称列表
     */
    public List<ModelProviderEnum> getAvailableProviders() {
        return chatClientFactory.getAvailableProviders();
    }
    
    /**
     * 检索知识库上下文
     */
    private String retrieveContext(String code, String language) {
        try {
            if (knowledgeBaseService == null) return null;
            
            // 构造查询：使用语言和代码片段的前500个字符 (减小查询长度，避免噪音)
            String query = "Language: " + language + "\nCode Snippet: " + 
                    code.substring(0, Math.min(code.length(), 500));
            
            // 使用 searchSnippets 获取切片，而不是完整文档
            List<String> snippets = knowledgeBaseService.searchSnippets(query, 3);
            if (snippets.isEmpty()) return null;
            
            StringBuilder sb = new StringBuilder();
            sb.append("相关代码规范与最佳实践：\n");
            for (int i = 0; i < snippets.size(); i++) {
                sb.append(i + 1).append(". ").append(snippets.get(i)).append("\n\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("RAG retrieval failed: {}", e.getMessage());
            return null;
        }
    }
}
