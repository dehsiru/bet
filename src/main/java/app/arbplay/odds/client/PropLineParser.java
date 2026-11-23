package app.arbplay.odds.client;

import app.arbplay.odds.model.MatchOdds;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the prop-line odds response into one MatchOdds per (event, bookmaker).
 * events[].bookmakers[].markets[key=h2h].outcomes[] with side = home / draw / away and
 * an American price (150 = 2.50, -200 = 1.50).
 */
@Component
public class PropLineParser {

    private static final Map<String, String> SIDE_TO_OUTCOME = Map.of("home", "1", "draw", "X", "away", "2");

    public List<MatchOdds> parse(JsonNode events) {
        List<MatchOdds> result = new ArrayList<>();
        if (events == null || !events.isArray()) {
            return result;
        }
        for (JsonNode event : events) {
            if (event.path("is_outright").asBoolean(false)) {
                continue;
            }
            String sport = event.path("sport_key").asText();
            String eventId = sport + ":" + event.path("id").asText();
            Instant start = parseInstant(event.path("commence_time").asText(null));
            boolean live = event.path("live").asBoolean(false);

            for (JsonNode bookmaker : event.path("bookmakers")) {
                JsonNode market = mainH2hMarket(bookmaker);
                if (market == null) {
                    continue;
                }
                Map<String, Double> outcomes = outcomes(market);
                if (outcomes.size() < 2) {
                    continue;
                }
                Instant lastUpdate = parseInstant(market.path("last_update").asText(null));
                if (lastUpdate == null) {
                    lastUpdate = parseInstant(bookmaker.path("last_update").asText(null));
                }
                String key = bookmaker.path("key").asText();
                result.add(new MatchOdds(key, bookmaker.path("title").asText(key), sport,
                        event.path("home_team").asText(), event.path("away_team").asText(),
                        start, live, eventId, outcomes, lastUpdate));
            }
        }
        return result;
    }

    /** The full-game, not suspended h2h (1X2 / moneyline) market. */
    private static JsonNode mainH2hMarket(JsonNode bookmaker) {
        for (JsonNode market : bookmaker.path("markets")) {
            if ("h2h".equals(market.path("key").asText())
                    && isNullOrMissing(market.path("period"))
                    && isNullOrMissing(market.path("suspended_at"))) {
                return market;
            }
        }
        return null;
    }

    private static Map<String, Double> outcomes(JsonNode market) {
        Map<String, Double> outcomes = new LinkedHashMap<>();
        for (JsonNode outcome : market.path("outcomes")) {
            String key = SIDE_TO_OUTCOME.get(outcome.path("side").asText());
            double decimal = americanToDecimal(outcome.path("price").asDouble(0));
            if (key != null && decimal > 1.0) {
                outcomes.put(key, decimal);
            }
        }
        return outcomes;
    }

    private static boolean isNullOrMissing(JsonNode node) {
        return node.isNull() || node.isMissingNode();
    }

    /**
     * +150 -> 2.50, -200 -> 1.50. Rounded to 2 decimals like the bookmakers show them
     * (the feed's American prices are rounded, e.g. 1.95 arrives as -105 = 1.9524).
     */
    static double americanToDecimal(double american) {
        double decimal;
        if (american >= 100) {
            decimal = 1.0 + american / 100.0;
        } else if (american <= -100) {
            decimal = 1.0 + 100.0 / -american;
        } else {
            return 0;
        }
        return Math.round(decimal * 100.0) / 100.0;
    }

    private static Instant parseInstant(String text) {
        try {
            return text == null ? null : Instant.parse(text);
        } catch (Exception e) {
            return null;
        }
    }
}
