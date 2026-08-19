package io.github.bovinemagnet.electrome.market.cdr;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CdrRegisterClientTest {

    private static String fixture() throws IOException {
        return Files.readString(
                Path.of("src/test/resources/register-brands.json"), StandardCharsets.UTF_8);
    }

    @Test
    void parsesEveryBrand() throws IOException {
        var brands = CdrRegisterClient.parse(fixture());
        assertThat(brands).hasSizeGreaterThan(50);
        assertThat(brands).allSatisfy(b -> assertThat(b.brandName()).isNotBlank());
    }

    @Test
    void usesProductBaseUriNotPublicBaseUri() {
        // publicBaseUri is the retailer's own CDR host and does not serve product reference
        // data. Reading the wrong field is why a retailer appears to publish no plans at all.
        var json = """
                {"data": [{
                  "brandName": "Arcline by RACV",
                  "publicBaseUri": "https://public.cdr.energy.arcline.com.au",
                  "productBaseUri": "https://cdr.energymadeeasy.gov.au/energy-locals"
                }]}
                """;
        var brands = CdrRegisterClient.parse(json);
        assertThat(brands).hasSize(1);
        assertThat(brands.get(0).productBaseUri())
                .isEqualTo("https://cdr.energymadeeasy.gov.au/energy-locals");
        assertThat(brands.get(0).slug()).isEqualTo("energy-locals");
    }

    @Test
    void marksBrandsWithoutAProductBaseUriUnusable() {
        var json = """
                {"data": [
                  {"brandName": "With", "productBaseUri": "https://example.test/x"},
                  {"brandName": "Without"}
                ]}
                """;
        var brands = CdrRegisterClient.parse(json);
        assertThat(brands.get(0).usable()).isTrue();
        assertThat(brands.get(1).usable()).isFalse();
        assertThat(brands.get(1).slug()).isEqualTo("without");
    }

    @Test
    void mostRealBrandsAreUsable() throws IOException {
        var brands = CdrRegisterClient.parse(fixture());
        assertThat(brands.stream().filter(RetailerBrand::usable).count()).isGreaterThan(70);
    }

    @Test
    void mostBrandsAreServedFromTheAerHost() throws IOException {
        // Nearly every retailer's product data is hosted centrally rather than self-served,
        // which is what makes a single harvest practical.
        var aerHosted = CdrRegisterClient.parse(fixture()).stream()
                .filter(RetailerBrand::usable)
                .filter(b -> b.productBaseUri().contains("energymadeeasy.gov.au"))
                .count();
        assertThat(aerHosted).isGreaterThan(70);
    }

    @Test
    void toleratesAnEmptyRegister() {
        assertThat(CdrRegisterClient.parse("{\"data\": []}")).isEmpty();
    }
}
