package cz.codingmonkey.bookstore.recommendation;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Computes embeddings in-process with the ONNX version of all-MiniLM-L6-v2, so no embedding service is needed. The
 * model is downloaded on first start and cached (see {@link RecommendationProperties}). Any other Spring AI
 * {@link EmbeddingModel} with 384 dimensions can replace it; a different dimension needs a migration of
 * {@code book_embedding.embedding}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RecommendationProperties.class)
class EmbeddingConfiguration {

    @Bean
    EmbeddingModel embeddingModel(RecommendationProperties properties) {
        var model = new TransformersEmbeddingModel();
        model.setModelResource(properties.modelUri());
        model.setTokenizerResource(properties.tokenizerUri());
        model.setResourceCacheDirectory(properties.cacheDir());
        return model;
    }
}
