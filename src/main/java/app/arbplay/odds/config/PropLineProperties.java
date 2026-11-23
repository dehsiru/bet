package app.arbplay.odds.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * @param sports      sport keys fetched on each refresh (one API call each, see GET /v1/sports)
 * @param bookmakers  default bookmaker filter; empty = all bookmakers
 * @param cacheSeconds how long fetched odds are reused, to save daily quota
 */
@ConfigurationProperties(prefix = "propline")
public record PropLineProperties(
        String baseUrl,
        String apiKey,
        int timeoutSeconds,
        List<String> sports,
        List<String> bookmakers,
        Map<String, List<String>> groups,
        int cacheSeconds,
        double defaultStake) {

    /**
     * Bookmakers owned by the same group: an arbitrage may use at most one bookmaker of each group.
     * Keys present in the prop-line feed are included next to the Greek brands.
     */
    private static final Map<String, List<String>> DEFAULT_GROUPS = Map.of(
            "entain", List.of("bwin", "sportingbet", "vistabet", "ladbrokes", "ladbrokes_be", "coral", "betmgm"),
            "flutter", List.of("paddypower", "fanduel", "betfair_ex", "pokerstars", "sisal"),
            "kaizen", List.of("stoiximan", "betano"),
            "superbet", List.of("superbet", "superbet_pl", "superbet_ro"),
            "codere", List.of("codere", "coderemx"),
            "fortuna", List.of("fortuna_cz", "fortuna_pl"),
            "polymarket", List.of("polymarket", "polymarket_us"));

    public PropLineProperties {
        baseUrl = baseUrl == null ? "https://api.prop-line.com" : baseUrl;
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("PROPLINE_API_KEY");
        }
        timeoutSeconds = timeoutSeconds <= 0 ? 20 : timeoutSeconds;
        cacheSeconds = cacheSeconds <= 0 ? 120 : cacheSeconds;
        defaultStake = defaultStake <= 0 ? 100 : defaultStake;
        sports = sports == null ? List.of(
                "soccer_greek_super_league", "soccer_epl", "soccer_la_liga", "soccer_serie_a",
                "soccer_uefa_champions_league", "basketball_euroleague", "basketball_greece_basket_league") : sports;
        // Bookmakers from the feed that operate in Greece.
        bookmakers = bookmakers == null ? List.of(
                "stoiximan", "pamestoixima", "elabet", "winmasters", "interwetten", "meridianbet") : bookmakers;
        groups = groups == null || groups.isEmpty() ? DEFAULT_GROUPS : groups;
    }

    /** bookmaker key -> group name. */
    public Map<String, String> groupByBookmaker() {
        Map<String, String> index = new HashMap<>();
        groups.forEach((group, members) -> members.forEach(m -> index.put(m.trim().toLowerCase(Locale.ROOT), group)));
        return index;
    }
}
