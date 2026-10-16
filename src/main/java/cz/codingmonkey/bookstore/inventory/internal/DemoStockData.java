package cz.codingmonkey.bookstore.inventory.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import cz.codingmonkey.bookstore.bookcatalog.BookCreatedEvent;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * With the {@code demo} profile, stocks the catalog's demo books ({@code demo-data/stock.json}, matched by ISBN)
 * as they are created. Books created later by staff aren't in the file and are left alone; restarts don't create
 * books, so nothing is stocked twice.
 */
@Component
@Profile("demo")
class DemoStockData {

    static final String LOCATION = "demo-data/stock.json";

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Data(List<Entry> stock) {
    }

    /** @param title only for people reading the file */
    record Entry(String isbn, String title, int quantity) {
    }

    private final InventoryService inventory;
    private final Map<String, Integer> quantitiesByIsbn;

    DemoStockData(InventoryService inventory, JsonMapper json) throws IOException {
        this.inventory = inventory;
        try (InputStream in = new ClassPathResource(LOCATION).getInputStream()) {
            this.quantitiesByIsbn = json.readValue(in, Data.class).stock().stream()
                    .collect(Collectors.toMap(Entry::isbn, Entry::quantity));
        }
    }

    @ApplicationModuleListener
    void on(BookCreatedEvent event) {
        Integer quantity = quantitiesByIsbn.get(event.isbn());
        if (quantity != null && quantity > 0) {
            inventory.restock(event.bookId(), quantity);
        }
    }
}
