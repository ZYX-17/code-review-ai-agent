package com.codeguardian.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import lombok.extern.slf4j.Slf4j;
import com.codeguardian.enums.ModelProviderEnum;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Spring AI配置类
 * 
 * <p>配置多个AI模型提供商，使用Spring AI的统一接口和自动配置特性</p>
 * 
 * @author 苏三
 * @since 1.0.0
 */
@Configuration
@Slf4j
public class SpringAIConfig {

    private final Environment environment;

    public SpringAIConfig(Environment environment) {
        this.environment = environment;
    }
    
    @Value("${spring.ai.qwen.api-key:${app.rag.qwen.api-key:}}")
    private String qwenApiKey;
    
    @Value("${spring.ai.qwen.base-url:${app.rag.qwen.base-url:https://dashscope.aliyuncs.com/compatible-mode}}")
    private String qwenBaseUrl;
    
    @Value("${spring.ai.qwen.chat.options.model:${app.rag.qwen.chat.options.model:qwen3-max}}")
    private String qwenModel;

    @Value("${app.rag.embedding.api-key:}")
    private String ragEmbeddingApiKey;

    @Value("${app.rag.embedding.base-url:https://dashscope.aliyuncs.com/compatible-mode}")
    private String ragEmbeddingBaseUrl;

    @Value("${app.rag.embedding.model:text-embedding-v3}")
    private String ragEmbeddingModel;

    @Value("${app.rag.embedding.dimensions:1024}")
    private int ragEmbeddingDimensions;
    
    @Value("${spring.ai.openai.api-key:}")
    private String openAiApiKey;
    
    @Value("${spring.ai.openai.base-url:https://api.openai.com}")
    private String openAiBaseUrl;
    
    @Value("${spring.ai.openai.chat.options.model:gpt-3.5-turbo}")
    private String openAiModel;
    
    @Value("${spring.ai.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;
    
    @Value("${spring.ai.ollama.chat.options.model:deepseek-r1:8b}")
    private String ollamaModel;
    
    /**
     * 注入Spring AI自动配置的Ollama ChatModel bean
     * 使用required=false确保即使bean不存在也不会报错
     */
    @Autowired(required = false)
    @Qualifier("ollamaChatModel")
    private ChatModel ollamaChatModel;
    
    /**
     * OpenAI ChatModel Bean
     * 只有在配置了非空API Key时才创建，避免启动失败
     */
    @Bean(name = "openAiChatModel")
    @ConditionalOnExpression("!'${spring.ai.openai.api-key:}'.isEmpty()")
    public ChatModel openAiChatModel() {
        
        log.info("初始化OpenAI ChatModel: baseUrl={}, model={}", openAiBaseUrl, openAiModel);
        
        OpenAiApi openAiApi = new OpenAiApi(openAiBaseUrl, openAiApiKey);
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .withModel(openAiModel)
                .withTemperature(0.3)
                .build();
        
        return new OpenAiChatModel(openAiApi, options);
    }
    
    /**
     * Qwen ChatModel Bean (使用OpenAI兼容接口)
     * 注意：Spring AI没有原生的Qwen支持，我们使用OpenAI兼容模式
     */
    @Bean(name = "qwenChatModel")
    @ConditionalOnExpression("!('${spring.ai.qwen.api-key:${app.rag.qwen.api-key:}}').isEmpty()")
    public ChatModel qwenChatModel() {
        log.info("初始化Qwen ChatModel: baseUrl={}, model={}", qwenBaseUrl, qwenModel);
        OpenAiApi qwenApi = new OpenAiApi(qwenBaseUrl, qwenApiKey);
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .withModel(qwenModel)
                .withTemperature(0.3)
                .build();
        
        log.info("Qwen ChatModel配置完成: baseUrl={}, model={}, 最终API端点: {}/v1/chat/completions",
                qwenBaseUrl, qwenModel, qwenBaseUrl);
        return new OpenAiChatModel(qwenApi, options);
    }

    /**
     * Qwen EmbeddingModel（DashScope OpenAI 兼容接口）
     *
     * <p>只有配置了 Qwen API Key 时才会创建，用于启用 pgvector 向量检索。</p>
     */
    @Bean(name = "qwenEmbeddingModel")
    @ConditionalOnExpression("!('${app.rag.embedding.api-key:}').isEmpty() || " +
            "!('${spring.ai.qwen.api-key:${app.rag.qwen.api-key:}}').isEmpty()")
    public EmbeddingModel qwenEmbeddingModel() {
        String apiKey = StringUtils.hasText(ragEmbeddingApiKey) ? ragEmbeddingApiKey : qwenApiKey;
        if (!StringUtils.hasText(ragEmbeddingApiKey)) {
            log.info("未配置 app.rag.embedding.api-key，复用 spring.ai.qwen.api-key 作为 embedding Key");
        }

        log.info("初始化Qwen EmbeddingModel: baseUrl={}, model={}, dimensions={}",
                ragEmbeddingBaseUrl, ragEmbeddingModel, ragEmbeddingDimensions);

        OpenAiApi qwenApi = new OpenAiApi(ragEmbeddingBaseUrl, apiKey);
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .withModel(ragEmbeddingModel)
                .withDimensions(ragEmbeddingDimensions)
                .build();

        return new OpenAiEmbeddingModel(qwenApi, MetadataMode.EMBED, options);
    }
    
    /**
     * 默认ChatModel (使用Qwen，如果不可用则使用OpenAI)
     * 使用@Lazy避免循环依赖
     */
    @Bean
    @Primary
    public ChatModel defaultChatModel(
            @Lazy @Qualifier("qwenChatModel") ObjectProvider<ChatModel> qwenChatModelProvider,
            @Lazy @Qualifier("openAiChatModel") ObjectProvider<ChatModel> openAiChatModelProvider) {
        ChatModel qwenChatModel = qwenChatModelProvider.getIfAvailable();
        ChatModel openAiChatModel = openAiChatModelProvider.getIfAvailable();

        if (qwenChatModel != null) {
            log.info("使用Qwen作为默认ChatModel");
            return qwenChatModel;
        }
        if (openAiChatModel != null) {
            log.info("使用OpenAI作为默认ChatModel");
            return openAiChatModel;
        }
        log.warn("没有可用的ChatModel，将返回null");
        return null;
    }
    

    
    /**
     * ChatModel映射，用于根据提供商名称获取对应的ChatModel
     * 使用@Lazy避免循环依赖
     */
    @Bean
    public Map<String, ChatModel> chatModelMap(
            @Lazy @Qualifier("qwenChatModel") ObjectProvider<ChatModel> qwenChatModelProvider,
            @Lazy @Qualifier("openAiChatModel") ObjectProvider<ChatModel> openAiChatModelProvider) {
        Map<String, ChatModel> modelMap = new HashMap<>();
        ChatModel qwenChatModel = qwenChatModelProvider.getIfAvailable();
        ChatModel openAiChatModel = openAiChatModelProvider.getIfAvailable();
        
        // 自定义的OpenAI ChatModel（仅在API key存在时创建）
        if (openAiChatModel != null) {
            modelMap.put(ModelProviderEnum.OPENAI.getCode(), openAiChatModel);
            log.info("注册OpenAI ChatModel (自定义配置)");
        }
        
        // 使用Spring AI自动配置的Ollama ChatModel
        if (ollamaChatModel != null && isDeepSeekConfigured()) {
            modelMap.put(ModelProviderEnum.DEEPSEEK.getCode(), ollamaChatModel);
            log.info("注册Ollama ChatModel (DeepSeek, 来自Spring AI自动配置)");
        }
        
        // 自定义的Qwen ChatModel
        if (qwenChatModel != null) {
            modelMap.put(ModelProviderEnum.QWEN.getCode(), qwenChatModel);
            log.info("注册Qwen ChatModel (自定义配置)");
        }
        
        log.info("已注册的ChatModel: {}", modelMap.keySet());
        if (modelMap.isEmpty()) {
            log.warn("警告：没有可用的ChatModel，应用可能无法正常工作");
        }
        return modelMap;
    }

    private boolean isDeepSeekConfigured() {
        String baseUrl = environment.getProperty("spring.ai.ollama.base-url");
        if (!StringUtils.hasText(baseUrl)) {
            baseUrl = environment.getProperty("app.rag.ollama.base-url");
        }
        if (!StringUtils.hasText(baseUrl)) {
            return false;
        }
        return isOllamaReachable(baseUrl);
    }

    /**
     * 只有 Ollama 真正可达时才注册 DeepSeek 本地模型，避免下拉框出现不可用选项
     */
    private boolean isOllamaReachable(String baseUrl) {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(1))
                    .build();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(baseUrl.replaceAll("/+$", "") + "/api/tags"))
                    .timeout(java.time.Duration.ofSeconds(1))
                    .GET()
                    .build();
            java.net.http.HttpResponse<Void> response = client.send(
                    request, java.net.http.HttpResponse.BodyHandlers.discarding());
            boolean reachable = response.statusCode() >= 200 && response.statusCode() < 500;
            if (!reachable) {
                log.warn("Ollama 不可用（HTTP {}），不注册 DeepSeek 本地模型", response.statusCode());
            }
            return reachable;
        } catch (Exception e) {
            log.warn("Ollama 未启动或不可达，不注册 DeepSeek 本地模型: {}", e.getMessage());
            return false;
        }
    }
}
