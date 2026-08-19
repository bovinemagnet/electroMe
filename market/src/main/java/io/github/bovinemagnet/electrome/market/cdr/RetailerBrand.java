package io.github.bovinemagnet.electrome.market.cdr;

import java.util.Locale;

/**
 * One retailer brand from the CDR register.
 *
 * @param productBaseUri the host that serves product reference data. Not to be confused with
 *     {@code publicBaseUri}, which is the retailer's own CDR host and does not serve it. Arcline
 *     by RACV, for instance, publishes its public host as arcline.com.au while its product data
 *     is served from energymadeeasy.gov.au — reading the wrong field finds no plans at all.
 * @param lastUpdated when the register last saw this brand change
 */
public record RetailerBrand(String brandName, String productBaseUri, String lastUpdated) {

    public boolean usable() {
        return productBaseUri != null && !productBaseUri.isBlank();
    }

    /** A short identifier for logs and cache keys, taken from the base URI's last segment. */
    public String slug() {
        if (!usable()) {
            return brandName == null ? "unknown" : brandName.toLowerCase(Locale.ROOT);
        }
        int lastSlash = productBaseUri.lastIndexOf('/');
        return lastSlash < 0 ? productBaseUri : productBaseUri.substring(lastSlash + 1);
    }
}
