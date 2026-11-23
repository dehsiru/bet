package app.arbplay.odds.model;

/** "45δ", "3λ", "2ω" — how long ago the odds were refreshed. */
public final class AgeFormat {

    private AgeFormat() {
    }

    public static String format(long seconds) {
        if (seconds == Long.MAX_VALUE) {
            return "?";
        }
        if (seconds < 60) {
            return seconds + "δ";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "λ";
        }
        return (seconds / 3600) + "ω";
    }
}
