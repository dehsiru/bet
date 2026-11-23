package app.arbplay.odds.service;

import app.arbplay.odds.client.PropLineParser;
import app.arbplay.odds.model.ArbResult;
import app.arbplay.odds.model.MatchOdds;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ArbitrageCalculatorTest {

    private final ArbitrageCalculator calc = new ArbitrageCalculator();
    private final Instant kickoff = Instant.parse("2026-11-29T17:00:00Z");

    private MatchOdds odds(String bookmaker, String eventId, double one, double x, double two) {
        return new MatchOdds(bookmaker, bookmaker, "soccer", "Olympiacos", "PAOK", kickoff, false, eventId,
                Map.of("1", one, "X", x, "2", two), Instant.now().minusSeconds(90));
    }

    @Test
    void findsArbitrageAcrossBookmakersAndSplitsStake() {
        List<ArbResult> results = calc.calculate(List.of(
                odds("stoiximan", "e1", 2.10, 3.20, 3.80),
                odds("novibet", "e1", 1.95, 3.60, 3.90),
                odds("bwin", "e1", 2.00, 3.30, 4.20)), 100);

        assertThat(results).hasSize(1);
        ArbResult r = results.get(0);
        assertThat(r.inverseSum()).isCloseTo(1 / 2.10 + 1 / 3.60 + 1 / 4.20, within(1e-9));
        assertThat(r.profitPercent()).isCloseTo(0.8, within(0.001));
        assertThat(r.isArbitrage()).isTrue();
        assertThat(r.betBookmakerCount()).isEqualTo(3);
        assertThat(r.bets()).extracting(b -> b.bookmaker()).containsExactly("stoiximan", "novibet", "bwin");
        assertThat(r.bets().get(0).ageText()).isEqualTo("1λ");
        r.bets().forEach(b -> assertThat(b.stake() * b.odds()).isCloseTo(r.payout(), within(1e-9)));
        assertThat(r.bets().stream().mapToDouble(b -> b.stake()).sum()).isCloseTo(100, within(1e-9));
    }

    @Test
    void needsADifferentBookmakerPerOutcomeAndSortsByLowestS() {
        List<ArbResult> results = calc.calculate(List.of(
                // e1: only 2 bookmakers for 3 outcomes -> no valid combination.
                odds("stoiximan", "e1", 2.10, 3.20, 3.80),
                odds("novibet", "e1", 2.30, 3.60, 4.00),
                // e2: 3 bookmakers, no arbitrage.
                odds("stoiximan", "e2", 2.10, 3.20, 3.80),
                odds("novibet", "e2", 2.00, 3.30, 3.70),
                odds("elabet", "e2", 2.05, 3.10, 3.60),
                // e3: 3 bookmakers, arbitrage.
                odds("stoiximan", "e3", 2.10, 3.20, 3.80),
                odds("novibet", "e3", 1.95, 3.60, 3.90),
                odds("bwin", "e3", 2.00, 3.30, 4.20)), 100);

        assertThat(results).extracting(ArbResult::eventId).containsExactly("e3", "e2");
        assertThat(results.get(0).isArbitrage()).isTrue();
        assertThat(results.get(1).isArbitrage()).isFalse();
        results.forEach(r -> assertThat(r.betBookmakerCount()).isEqualTo(3));
    }

    @Test
    void usesAtMostOneBookmakerPerGroup() {
        Map<String, String> groups = Map.of("bwin", "entain", "sportingbet", "entain", "vistabet", "entain");
        List<MatchOdds> event = List.of(
                odds("bwin", "e1", 2.30, 3.00, 3.00),
                odds("sportingbet", "e1", 2.00, 3.70, 3.00),
                odds("vistabet", "e1", 2.00, 3.00, 4.30),
                odds("stoiximan", "e1", 2.10, 3.40, 3.90),
                odds("elabet", "e1", 2.05, 3.50, 4.00));

        // Without groups: bwin / sportingbet / vistabet, all Entain.
        ArbResult free = calc.calculate(event, 100).get(0);
        assertThat(free.bets()).extracting(b -> b.bookmaker()).containsExactly("bwin", "sportingbet", "vistabet");

        // With groups: one Entain bookmaker at most, and a different bookmaker per outcome.
        ArbResult r = calc.calculate(event, 100, groups).get(0);
        // Best valid: bwin 2.30 + elabet 3.50 + stoiximan 3.90 (S 0.9769) beats
        // bwin 2.30 + stoiximan 3.40 + elabet 4.00 (S 0.9789) and
        // stoiximan 2.10 + elabet 3.50 + vistabet 4.30 (S 0.9945).
        assertThat(r.bets()).extracting(b -> b.bookmaker()).containsExactly("bwin", "elabet", "stoiximan");
        assertThat(r.inverseSum()).isCloseTo(1 / 2.30 + 1 / 3.50 + 1 / 3.90, within(1e-9));
    }

    @Test
    void sameBookmakerIsNeverUsedTwice() {
        ArbResult r = calc.calculate(List.of(
                odds("bwin", "e1", 2.50, 3.00, 4.50),
                odds("vistabet", "e1", 2.40, 3.10, 3.00),
                odds("stoiximan", "e1", 2.00, 3.80, 3.50)), 100).get(0);

        // bwin has the best 1 and 2, but may only take one of them.
        assertThat(r.bets()).extracting(b -> b.bookmaker()).doesNotHaveDuplicates();
        // bwin 2.50 + stoiximan 3.80 + vistabet 3.00 = 0.9298 vs vistabet 2.40 + stoiximan 3.80 + bwin 4.50 = 0.9019
        assertThat(r.bets()).extracting(b -> b.bookmaker()).containsExactly("vistabet", "stoiximan", "bwin");
    }

    @Test
    void parsesPropLineResponseAndConvertsAmericanOdds() throws Exception {
        String json = """
                [{"id":"723090","sport_key":"soccer_greek_super_league","home_team":"Volos NFC",
                  "away_team":"Asteras Tripolis","commence_time":"2026-11-28T15:00:00Z","live":false,
                  "is_outright":false,"bookmakers":[{"key":"bet25","title":"Bet25",
                  "last_update":"2026-11-23T21:22:14Z","markets":[
                  {"key":"h2h","period":null,"suspended_at":null,"last_update":"2026-11-23T21:22:40Z","outcomes":[
                    {"name":"Volos","price":150,"side":"home"},
                    {"name":"Asteras","price":-200,"side":"away"},
                    {"name":"Draw","price":220,"side":"draw"}]}]}]}]
                """;
        List<MatchOdds> parsed = new PropLineParser().parse(new ObjectMapper().readTree(json));

        assertThat(parsed).hasSize(1);
        MatchOdds o = parsed.get(0);
        assertThat(o.eventId()).isEqualTo("soccer_greek_super_league:723090");
        assertThat(o.lastUpdate()).isEqualTo(Instant.parse("2026-11-23T21:22:40Z"));
        assertThat(o.outcomes().get("1")).isCloseTo(2.50, within(1e-9));
        assertThat(o.outcomes().get("X")).isCloseTo(3.20, within(1e-9));
        assertThat(o.outcomes().get("2")).isCloseTo(1.50, within(1e-9));
    }
}
