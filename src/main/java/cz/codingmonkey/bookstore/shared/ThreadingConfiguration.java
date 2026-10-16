package cz.codingmonkey.bookstore.shared;

import java.util.concurrent.Executors;
import org.springframework.boot.tomcat.TomcatProtocolHandlerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * HTTP requests run on virtual threads: they mostly wait for the database, which virtual threads make cheap.
 * <p>
 * Background work (the async event listeners) deliberately stays on Spring Boot's regular thread pool
 * ({@code spring.task.execution.pool} in {@code application.yml}): computing embeddings is CPU-bound native code that
 * gains nothing from virtual threads, and a fixed pool bounds and queues that work by itself. That's also why
 * virtual threads aren't enabled globally with {@code spring.threads.virtual.enabled}, which would move the pool to
 * virtual threads too.
 */
@Configuration(proxyBeanMethods = false)
class ThreadingConfiguration {

    @Bean
    TomcatProtocolHandlerCustomizer<?> virtualThreadsForHttpRequests() {
        return protocolHandler -> protocolHandler.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }
}
