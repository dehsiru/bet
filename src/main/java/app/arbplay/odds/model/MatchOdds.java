package app.arbplay.odds.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Match-result odds of one event at one bookmaker, in decimal.
 * outcomes: "1", "X", "2" (or just "1", "2" for sports without a draw) -> odds.
 * lastUpdate: when the provider last refreshed these odds.
 */
public record MatchOdds(
        String bookmaker,
        String bookmakerTitle,
        String sport,
        String home,
        String away,
        Instant startTime,
        boolean live,
        String eventId,
        Map<String, Double> outcomes,
        Instant lastUpdate) {

    public long ageSeconds() {
        return lastUpdate == null ? Long.MAX_VALUE : Math.max(0, Duration.between(lastUpdate, Instant.now()).getSeconds());
    }

    public String ageText() {
        return AgeFormat.format(ageSeconds());
    }
}
