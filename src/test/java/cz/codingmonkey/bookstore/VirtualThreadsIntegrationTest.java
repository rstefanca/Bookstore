package cz.codingmonkey.bookstore;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({TestcontainersConfiguration.class, VirtualThreadsIntegrationTest.ThreadProbe.class})
class VirtualThreadsIntegrationTest {

    /** Test-only endpoint reporting the thread the request runs on, open to anyone. */
    @RestController
    static class ThreadProbe {

        @GetMapping("/test/thread")
        Map<String, Object> thread() {
            return Map.of("virtual", Thread.currentThread().isVirtual());
        }

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        SecurityFilterChain openTestEndpoints(HttpSecurity http) throws Exception {
            return http.securityMatcher("/test/**")
                    .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                    .build();
        }
    }

    @Autowired
    RestTestClient client;

    @Autowired
    @Qualifier("applicationTaskExecutor")
    TaskExecutor taskExecutor;

    @Test
    void handlesHttpRequestsOnVirtualThreads() {
        client.get().uri("/test/thread")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.virtual").isEqualTo(true);
    }

    @Test
    void runsBackgroundWorkOnABoundedPlatformThreadPool() throws Exception {
        var thread = new CompletableFuture<Thread>();
        taskExecutor.execute(() -> thread.complete(Thread.currentThread()));

        assertThat(thread.get(5, TimeUnit.SECONDS)).satisfies(worker -> {
            assertThat(worker.isVirtual()).isFalse();
            assertThat(worker.getName()).startsWith("bookstore-task-");
        });
        assertThat(taskExecutor).isInstanceOfSatisfying(ThreadPoolTaskExecutor.class, pool -> {
            assertThat(pool.getCorePoolSize()).isEqualTo(8);
            assertThat(pool.getMaxPoolSize()).isEqualTo(8);
        });
    }
}
