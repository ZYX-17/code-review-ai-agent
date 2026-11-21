package com.codeguardian.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.PgVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 向量存储配置
 *
 * <p>仅在配置了 Qwen API Key（启用 Qwen EmbeddingModel）时创建 PgVectorStore。
 * 未配置时应用仍可正常启动，知识库退化为 BM25 检索。</p>
 */
@Configuration
public class VectorStoreConfig {

    @Value("${app.rag.embedding.dimensions:1024}")
    private int dimensions;

    @Bean
    @ConditionalOnExpression("!('${app.rag.embedding.api-key:}').isEmpty() || " +
            "!('${spring.ai.qwen.api-key:${app.rag.qwen.api-key:}}').isEmpty()")
    public VectorStore vectorStore(JdbcTemplate jdbcTemplate,
                                   @Qualifier("qwenEmbeddingModel") EmbeddingModel embeddingModel) {
        return new PgVectorStore.Builder(jdbcTemplate, embeddingModel)
                .withDimensions(dimensions)
                .withDistanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .withIndexType(PgVectorStore.PgIndexType.HNSW)
                .withVectorTableValidationsEnabled(false)
                .withInitializeSchema(true)
                .build();
    }
}
