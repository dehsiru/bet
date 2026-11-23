package app.arbplay.odds.client;

import app.arbplay.odds.config.PropLineProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Client for https://api.prop-line.com (the key goes in the X-API-Key header). */
@Component
public class PropLineClient {

    private final RestClient restClient;
    private final PropLineProperties props;

    public PropLineClient(RestClient propLineRestClient, PropLineProperties props) {
        this.restClient = propLineRestClient;
        this.props = props;
    }

    public record OddsResponse(JsonNode events, Integer dailyRemaining) {
    }

    /** GET /v1/sports/{sport}/odds?markets=h2h — match-result odds of every bookmaker. */
    public OddsResponse getH2hOdds(String sport) {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            throw new IllegalStateException("No API key: set propline.api-key in application.properties "
                    + "or the PROPLINE_API_KEY environment variable");
        }
        ResponseEntity<JsonNode> response = restClient.get()
                .uri(uri -> uri.path("/v1/sports/{sport}/odds")
                        .queryParam("markets", "h2h")
                        .build(sport))
                .header("X-API-Key", props.apiKey())
                .retrieve()
                .toEntity(JsonNode.class);
        String remaining = response.getHeaders().getFirst("x-daily-remaining");
        return new OddsResponse(response.getBody(), remaining == null ? null : Integer.valueOf(remaining));
    }
}
