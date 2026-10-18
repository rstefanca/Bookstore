package cz.codingmonkey.bookstore.customer.internal;

import static org.springframework.http.HttpStatus.NO_CONTENT;

import cz.codingmonkey.bookstore.customer.Address;
import cz.codingmonkey.bookstore.shared.CurrentUser;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in customer's saved shipping and invoice addresses.
 */
@RestController
@RequestMapping("/api/me/addresses")
@Tag(name = "Addresses", description = """
        Customers (`CUSTOMER`) keep one saved shipping address and one saved invoice address. Saving again replaces \
        the address; deleting an address that isn't saved does nothing.""")
class AddressController {

    private final AddressService addresses;

    AddressController(AddressService addresses) {
        this.addresses = addresses;
    }

    /**
     * Returns your saved addresses; one that isn't saved is {@code null}.
     */
    @GetMapping
    @ApiResponse(responseCode = "200", description = "Your saved shipping and invoice addresses")
    SavedAddressesView get() {
        return addresses.get(CurrentUser.requireId());
    }

    /**
     * Saves your shipping address, replacing the earlier one.
     */
    @PutMapping("/shipping")
    @ApiResponse(responseCode = "200", description = "The saved shipping address")
    @ApiResponse(responseCode = "400", description = "A field is missing, blank, too long or the country isn't a "
            + "two-letter code")
    Address saveShipping(@Valid @RequestBody Address address) {
        return addresses.saveShippingAddress(CurrentUser.requireId(), address);
    }

    /**
     * Saves your invoice address, replacing the earlier one.
     */
    @PutMapping("/invoice")
    @ApiResponse(responseCode = "200", description = "The saved invoice address")
    @ApiResponse(responseCode = "400", description = "A field is missing, blank, too long or the country isn't a "
            + "two-letter code")
    Address saveInvoice(@Valid @RequestBody Address address) {
        return addresses.saveInvoiceAddress(CurrentUser.requireId(), address);
    }

    /**
     * Deletes your saved shipping address, if any.
     */
    @DeleteMapping("/shipping")
    @ResponseStatus(NO_CONTENT)
    @ApiResponse(responseCode = "204", description = "The shipping address is no longer saved")
    void deleteShipping() {
        addresses.deleteShippingAddress(CurrentUser.requireId());
    }

    /**
     * Deletes your saved invoice address, if any.
     */
    @DeleteMapping("/invoice")
    @ResponseStatus(NO_CONTENT)
    @ApiResponse(responseCode = "204", description = "The invoice address is no longer saved")
    void deleteInvoice() {
        addresses.deleteInvoiceAddress(CurrentUser.requireId());
    }
}
