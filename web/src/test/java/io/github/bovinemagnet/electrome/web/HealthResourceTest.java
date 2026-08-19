package io.github.bovinemagnet.electrome.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class HealthResourceTest {

    @Test
    void reportsReady() {
        given().when().get("/health").then().statusCode(200).body(containsString("ready"));
    }
}
