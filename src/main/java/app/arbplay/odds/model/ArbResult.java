package app.arbplay.odds.model;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Result for one event across bookmakers.
 * inverseSum = S = sum(1/odds). S < 1 means arbitrage, profit = 1/S - 1.
 */
public record ArbResult(
        String eventId,
        String sport,
        String home,
        String away,
        Instant startTime,
        boolean live,
        Set<String> bookmakers,
        List<OutcomeBet> bets,
        double inverseSum,
        double profitPercent,
        double totalStake,
        double payout) {

    public boolean isArbitrage() {
        return inverseSum < 1.0;
    }

    /** How many different bookmakers the chosen bets are spread across. */
    public long betBookmakerCount() {
        return bets.stream().map(OutcomeBet::bookmaker).distinct().count();
    }
}
