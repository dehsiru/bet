package app.arbplay.odds.model;

/** The odds chosen for one outcome, and how much to stake on it. */
public record OutcomeBet(String outcome, MatchOdds source, double odds, double stake) {

    public String bookmaker() {
        return source.bookmaker();
    }

    public String bookmakerTitle() {
        return source.bookmakerTitle();
    }

    public String ageText() {
        return source.ageText();
    }

    /** Odds older than this are shown as stale in the UI. */
    public boolean stale() {
        return source.ageSeconds() > 180;
    }
}
