package com.codeguardian.service;

import com.codeguardian.config.AIConfigProperties;
import com.codeguardian.service.ai.PromptService;
import com.codeguardian.service.ai.factory.ChatClientFactory;
import com.codeguardian.service.ai.output.CodeReviewOutputParser;
import com.codeguardian.service.ai.tool.ToolRegistry;
import com.codeguardian.service.rag.KnowledgeBaseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AIModelServiceTest {

    @Mock
    private ChatClientFactory chatClientFactory;
    @Mock
    private PromptService promptService;
    @Mock
    private CodeReviewOutputParser outputParser;
    @Mock
    private KnowledgeBaseService knowledgeBaseService;
    @Mock
    private AIConfigProperties aiConfigProperties;
    @Mock
    private ToolRegistry toolRegistry;

    @InjectMocks
    private AIModelService aiModelService;

    @Test
    void should_throw_when_ai_disabled() {
        when(aiConfigProperties.getEnabled()).thenReturn(false);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> aiModelService.reviewCode("code", "java"));

        assertTrue(exception.getMessage().contains("AI审查已禁用"));
        verify(chatClientFactory, never()).createChatClient(any());
    }

    @Test
    void should_throw_when_no_provider_available() {
        when(aiConfigProperties.getEnabled()).thenReturn(true);
        when(chatClientFactory.hasAvailableProviders()).thenReturn(false);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> aiModelService.reviewCode("code", "java", "QWEN", true));

        assertTrue(exception.getMessage().contains("未配置可用的大模型"));
        verify(chatClientFactory, never()).createChatClient(any());
    }
}
