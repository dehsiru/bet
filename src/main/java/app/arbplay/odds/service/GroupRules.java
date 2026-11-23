package app.arbplay.odds.service;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * In one bet combination every outcome must be at a different bookmaker, and no two bookmakers may
 * belong to the same group (same owner, e.g. Entain: bwin / sportingbet / vistabet).
 * A bookmaker without a group is its own group.
 */
final class GroupRules {

    private GroupRules() {
    }

    static String groupOf(String bookmaker, Map<String, String> groupByBookmaker) {
        return groupByBookmaker.getOrDefault(bookmaker, "bk:" + bookmaker);
    }

    /** Same bookmaker, or two bookmakers of the same group. */
    static boolean conflict(String a, String b, Map<String, String> groupByBookmaker) {
        return groupOf(a, groupByBookmaker).equals(groupOf(b, groupByBookmaker));
    }

    static boolean conflictsWithAny(String bookmaker, Collection<String> chosen, Map<String, String> groupByBookmaker) {
        return chosen.stream().anyMatch(other -> conflict(bookmaker, other, groupByBookmaker));
    }

    /**
     * With up to 2 other bookmakers already chosen, at most 2*maxGroupSize bookmakers are excluded
     * (each chosen one blocks its whole group), so the best allowed price for an outcome is always
     * within this many top prices.
     */
    static int candidateLimit(Map<String, String> groupByBookmaker) {
        int maxGroupSize = groupByBookmaker.values().stream()
                .collect(Collectors.groupingBy(g -> g, Collectors.counting()))
                .values().stream().mapToInt(Long::intValue).max().orElse(1);
        return 2 * maxGroupSize + 1;
    }
}
