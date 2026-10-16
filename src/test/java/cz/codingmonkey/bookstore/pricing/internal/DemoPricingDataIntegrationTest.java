package cz.codingmonkey.bookstore.pricing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import cz.codingmonkey.bookstore.bookcatalog.BookCreatedEvent;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Demo prices, in the same (cached) context as the catalog's demo data test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles("demo")
@Import(TestcontainersConfiguration.class)
class DemoPricingDataIntegrationTest {

    @Autowired
    BookPriceRepository prices;

    @Autowired
    DemoPricingData demoPricing;

    @Test
    void pricesEveryListedDemoBook() {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(prices.count()).isEqualTo(24));
    }

    @Test
    void leavesBooksThatArentDemoBooksAlone() {
        long before = prices.count();

        demoPricing.on(new BookCreatedEvent(new BookId(999_999), "Not a demo book", List.of("Someone"), List.of(),
                "not-a-demo-isbn", null, 0, null));

        assertThat(prices.count()).isEqualTo(before);
    }
}
