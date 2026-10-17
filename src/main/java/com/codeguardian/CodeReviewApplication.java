package com.codeguardian;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 代码审查AI Agent主应用
 * 
 * @author CodeGuardian Team
 * @version 1.0.0
 */
@SpringBootApplication(exclude = {
    org.springframework.ai.autoconfigure.openai.OpenAiAutoConfiguration.class
})
public class CodeReviewApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(CodeReviewApplication.class, args);
    }
}


