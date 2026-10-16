package cz.codingmonkey.bookstore.pricing.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import cz.codingmonkey.bookstore.bookcatalog.BookCreatedEvent;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * With the {@code demo} profile, prices the catalog's demo books ({@code demo-data/prices.json}, matched by ISBN)
 * as they are created. Books created later by staff aren't in the file and are left alone; restarts don't create
 * books, so nothing is priced twice.
 */
@Component
@Profile("demo")
class DemoPricingData {

    static final String LOCATION = "demo-data/prices.json";

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Data(List<Entry> prices) {
    }

    /** @param title only for people reading the file */
    record Entry(String isbn, String title, BigDecimal amount) {
    }

    private final PricingService pricing;
    private final Map<String, BigDecimal> amountsByIsbn;

    DemoPricingData(PricingService pricing, JsonMapper json) throws IOException {
        this.pricing = pricing;
        try (InputStream in = new ClassPathResource(LOCATION).getInputStream()) {
            this.amountsByIsbn = json.readValue(in, Data.class).prices().stream()
                    .collect(Collectors.toMap(Entry::isbn, Entry::amount));
        }
    }

    @ApplicationModuleListener
    void on(BookCreatedEvent event) {
        BigDecimal amount = amountsByIsbn.get(event.isbn());
        if (amount != null) {
            pricing.setPrice(event.bookId(), amount, null);
        }
    }
}
