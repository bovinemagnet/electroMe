package io.github.bovinemagnet.electrome.view;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/**
 * Serialises a complete ECharts option object for embedding in a data attribute.
 *
 * <p>Chart configuration is built in Java so that it can be unit tested. The browser only
 * hands the resulting object to ECharts.
 */
public final class Charts {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Charts() {}

    public static String payload(Map<String, Object> option) {
        try {
            return MAPPER.writeValueAsString(option);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise chart option", e);
        }
    }
}
