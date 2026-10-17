package cz.codingmonkey.bookstore.inventory.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * How many copies to add or take out.
 *
 * @param quantity at least 1
 */
public record StockRequest(@Schema(example = "5") @NotNull @Positive Integer quantity) {
}
