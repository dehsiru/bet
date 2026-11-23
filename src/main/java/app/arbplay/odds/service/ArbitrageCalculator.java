package app.arbplay.odds.service;

import app.arbplay.odds.model.ArbResult;
import app.arbplay.odds.model.MatchOdds;
import app.arbplay.odds.model.OutcomeBet;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * For each event, picks one bookmaker per outcome so that S = sum(1/odds) is as low as possible,
 * with the rule that every outcome is at a different bookmaker and at most one bookmaker of each
 * group (same owner, e.g. Entain: bwin / sportingbet / vistabet) is used.
 * An event needs at least as many usable bookmakers as outcomes (3 for 1X2).
 * S < 1 means arbitrage, profit = 1/S - 1, stake_i = T * (1/odds_i) / S.
 */
@Component
public class ArbitrageCalculator {

    static final List<String> OUTCOME_ORDER = List.of("1", "X", "2");

    record Candidate(MatchOdds source, double price) {
    }

    /** Without groups: plain highest odds per outcome. */
    public List<ArbResult> calculate(List<MatchOdds> allOdds, double totalStake) {
        return calculate(allOdds, totalStake, Map.of());
    }

    /**
     * @param groupByBookmaker bookmaker key -> group name; bookmakers not in it are their own group
     * @return results sorted by lowest S first (= highest profit)
     */
    public List<ArbResult> calculate(List<MatchOdds> allOdds, double totalStake, Map<String, String> groupByBookmaker) {
        int topN = GroupRules.candidateLimit(groupByBookmaker);
        List<ArbResult> results = new ArrayList<>();
        for (List<MatchOdds> event : byEvent(allOdds).values()) {
            ArbResult r = evaluate(event, totalStake, groupByBookmaker, topN);
            if (r != null) {
                results.add(r);
            }
        }
        results.sort(Comparator.comparingDouble(ArbResult::inverseSum));
        return results;
    }

    static Map<String, List<MatchOdds>> byEvent(List<MatchOdds> allOdds) {
        Map<String, List<MatchOdds>> byEvent = new LinkedHashMap<>();
        for (MatchOdds odds : allOdds) {
            byEvent.computeIfAbsent(odds.eventId(), k -> new ArrayList<>()).add(odds);
        }
        return byEvent;
    }

    /** The largest outcome set any bookmaker offers (3-way 1X2 beats 2-way), in 1/X/2 order. */
    static List<String> outcomesOf(List<MatchOdds> event) {
        Set<String> outcomeSet = event.stream()
                .map(o -> o.outcomes().keySet())
                .max(Comparator.comparingInt(Set::size))
                .orElse(Set.of());
        return OUTCOME_ORDER.stream().filter(outcomeSet::contains).toList();
    }

    /** Best price per bookmaker for an outcome, highest first, at most topN. */
    static List<Candidate> candidates(List<MatchOdds> event, String outcome, int topN) {
        Map<String, Candidate> bestPerBookmaker = new HashMap<>();
        for (MatchOdds o : event) {
            Double price = o.outcomes().get(outcome);
            if (price != null) {
                bestPerBookmaker.merge(o.bookmaker(), new Candidate(o, price),
                        (a, b) -> a.price() >= b.price() ? a : b);
            }
        }
        return bestPerBookmaker.values().stream()
                .sorted(Comparator.comparingDouble(Candidate::price).reversed())
                .limit(topN)
                .toList();
    }

    /**
     * Picks one candidate per list so that sum(1/price) is lowest, without two bookmakers of the same
     * group among the picks and the fixed bookmakers. Returns null when no valid combination exists.
     */
    static Candidate[] bestCombination(List<List<Candidate>> candidates, List<String> fixedBookmakers,
                                       Map<String, String> groupByBookmaker) {
        Candidate[] best = new Candidate[candidates.size()];
        search(candidates, 0, new Candidate[candidates.size()], 0.0, best, Double.MAX_VALUE,
                fixedBookmakers, groupByBookmaker);
        return best.length > 0 && best[0] == null ? null : best;
    }

    private static double search(List<List<Candidate>> candidates, int index, Candidate[] chosen, double partialS,
                                 Candidate[] best, double bestS, List<String> fixed, Map<String, String> groups) {
        if (partialS >= bestS) {
            return bestS;
        }
        if (index == candidates.size()) {
            System.arraycopy(chosen, 0, best, 0, chosen.length);
            return partialS;
        }
        for (Candidate c : candidates.get(index)) {
            String bookmaker = c.source().bookmaker();
            boolean conflict = GroupRules.conflictsWithAny(bookmaker, fixed, groups);
            for (int i = 0; i < index && !conflict; i++) {
                conflict = GroupRules.conflict(bookmaker, chosen[i].source().bookmaker(), groups);
            }
            if (conflict) {
                continue;
            }
            chosen[index] = c;
            bestS = search(candidates, index + 1, chosen, partialS + 1.0 / c.price(), best, bestS, fixed, groups);
        }
        chosen[index] = null;
        return bestS;
    }

    ArbResult evaluate(List<MatchOdds> event, double totalStake, Map<String, String> groupByBookmaker, int topN) {
        List<String> outcomes = outcomesOf(event);
        if (outcomes.size() < 2) {
            return null;
        }
        List<List<Candidate>> candidates = outcomes.stream().map(o -> candidates(event, o, topN)).toList();
        Candidate[] best = bestCombination(candidates, List.of(), groupByBookmaker);
        if (best == null) {
            return null;
        }
        double s = 0;
        for (Candidate c : best) {
            s += 1.0 / c.price();
        }

        List<OutcomeBet> bets = new ArrayList<>();
        for (int i = 0; i < outcomes.size(); i++) {
            Candidate c = best[i];
            bets.add(new OutcomeBet(outcomes.get(i), c.source(), c.price(), totalStake * (1.0 / c.price()) / s));
        }

        Set<String> bookmakers = new LinkedHashSet<>();
        event.forEach(o -> bookmakers.add(o.bookmaker()));

        MatchOdds first = event.get(0);
        return new ArbResult(first.eventId(), first.sport(), first.home(), first.away(), first.startTime(),
                first.live(), bookmakers, bets, s, (1.0 / s - 1.0) * 100.0, totalStake, totalStake / s);
    }
}
