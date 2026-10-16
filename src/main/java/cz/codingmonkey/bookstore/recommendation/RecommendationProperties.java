package cz.codingmonkey.bookstore.recommendation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where to load the embedding model from. The defaults point to all-MiniLM-L6-v2 as published with Spring AI
 * 2.0.1, pinned to that release so embeddings don't change unnoticed. Point them to a local or internal copy
 * ({@code file:...} or {@code classpath:...}) to start without internet access.
 *
 * @param modelUri      ONNX model
 * @param tokenizerUri  matching tokenizer
 * @param cacheDir      where downloaded files are kept between restarts
 */
@ConfigurationProperties("bookstore.recommendation")
record RecommendationProperties(String modelUri, String tokenizerUri, String cacheDir) {
}
