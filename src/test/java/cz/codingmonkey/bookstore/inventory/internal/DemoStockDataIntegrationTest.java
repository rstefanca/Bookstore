package cz.codingmonkey.bookstore.inventory.internal;

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
 * Demo stock, in the same (cached) context as the catalog's demo data test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles("demo")
@Import(TestcontainersConfiguration.class)
class DemoStockDataIntegrationTest {

    @Autowired
    InventoryItemRepository stock;

    @Autowired
    DemoStockData demoStock;

    @Test
    void stocksEveryListedDemoBook() {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(stock.count()).isEqualTo(23));
        assertThat(stock.findAll()).extracting(InventoryItem::getQuantity).allMatch(quantity -> quantity > 0);
    }

    @Test
    void leavesBooksThatArentDemoBooksAlone() {
        long before = stock.count();

        demoStock.on(new BookCreatedEvent(new BookId(999_999), "Not a demo book", List.of("Someone"), List.of(),
                "not-a-demo-isbn", null, 0, null));

        assertThat(stock.count()).isEqualTo(before);
    }
}
