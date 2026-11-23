package app.arbplay.odds.web;

import app.arbplay.odds.config.PropLineProperties;
import app.arbplay.odds.model.ArbResult;
import app.arbplay.odds.model.MatchOdds;
import app.arbplay.odds.service.ArbitrageCalculator;
import app.arbplay.odds.service.OddsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Controller
public class OddsController {

    private final OddsService oddsService;
    private final ArbitrageCalculator calculator;
    private final PropLineProperties props;

    public OddsController(OddsService oddsService, ArbitrageCalculator calculator, PropLineProperties props) {
        this.oddsService = oddsService;
        this.calculator = calculator;
        this.props = props;
    }

    @GetMapping("/")
    public String index(@RequestParam(required = false) Double stake,
                        @RequestParam(required = false) String bookmakers,
                        @RequestParam(defaultValue = "") String sport,
                        @RequestParam(defaultValue = "false") boolean onlyArbs,
                        @RequestParam(defaultValue = "false") boolean includeLive,
                        @RequestParam(required = false) Integer maxAge,
                        @RequestParam(defaultValue = "false") boolean refresh,
                        Model model) {
        double totalStake = stake != null && stake > 0 ? stake : props.defaultStake();
        String bookmakerText = bookmakerText(bookmakers);
        OddsService.Snapshot snapshot = oddsService.get(refresh);

        List<MatchOdds> filtered = filter(snapshot.odds().stream(), bookmakerText, maxAge)
                .filter(o -> sport.isEmpty() || sport.equals(o.sport()))
                .filter(o -> includeLive || !o.live())
                .toList();

        List<ArbResult> results = calculator.calculate(filtered, totalStake, props.groupByBookmaker()).stream()
                .filter(r -> !onlyArbs || r.isArbitrage())
                .toList();

        addCommon(model, snapshot, bookmakerText, maxAge, includeLive);
        model.addAttribute("results", results);
        model.addAttribute("oddsCount", filtered.size());
        model.addAttribute("arbCount", results.stream().filter(ArbResult::isArbitrage).count());
        model.addAttribute("availableBookmakers",
                snapshot.odds().stream().map(MatchOdds::bookmaker).collect(Collectors.toCollection(TreeSet::new)));
        model.addAttribute("sports", props.sports());
        model.addAttribute("groups", props.groups());
        model.addAttribute("sport", sport);
        model.addAttribute("stake", totalStake);
        model.addAttribute("onlyArbs", onlyArbs);
        return "index";
    }

    /**
     * One match with all its odds. Uses the already loaded odds (no API call); the free bet
     * calculator runs in the browser on the odds embedded in the page, so it sends no requests.
     */
    @GetMapping("/match")
    public String match(@RequestParam String id,
                        @RequestParam(required = false) String bookmakers,
                        @RequestParam(defaultValue = "false") boolean includeLive,
                        @RequestParam(required = false) Integer maxAge,
                        Model model) {
        String bookmakerText = bookmakerText(bookmakers);
        OddsService.Snapshot snapshot = oddsService.cachedOnly();
        List<MatchOdds> event = filter(snapshot.odds().stream().filter(o -> o.eventId().equals(id)), bookmakerText, maxAge)
                .sorted(Comparator.comparing(MatchOdds::bookmakerTitle, String.CASE_INSENSITIVE_ORDER))
                .toList();

        Map<String, String> groups = props.groupByBookmaker();
        ArbResult best = event.isEmpty() ? null
                : calculator.calculate(event, 100, groups).stream().findFirst().orElse(null);
        Set<String> bestKeys = best == null ? Set.of()
                : best.bets().stream().map(b -> b.bookmaker() + "|" + b.outcome()).collect(Collectors.toSet());
        List<String> outcomes = Stream.of("1", "X", "2")
                .filter(o -> event.stream().anyMatch(m -> m.outcomes().containsKey(o)))
                .toList();

        // Data for the in-browser free bet calculator.
        List<Map<String, Object>> oddsData = event.stream().map(o -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("bk", o.bookmaker());
            row.put("title", o.bookmakerTitle());
            row.put("group", groups.get(o.bookmaker()));
            row.put("age", o.ageText());
            row.put("stale", o.ageSeconds() > 180);
            row.put("prices", o.outcomes());
            return row;
        }).toList();

        addCommon(model, snapshot, bookmakerText, maxAge, includeLive);
        model.addAttribute("id", id);
        model.addAttribute("event", event);
        model.addAttribute("first", event.isEmpty() ? null : event.get(0));
        model.addAttribute("outcomes", outcomes);
        model.addAttribute("best", best);
        model.addAttribute("bestKeys", bestKeys);
        model.addAttribute("groupByBookmaker", groups);
        model.addAttribute("calcData", Map.of("outcomes", outcomes, "odds", oddsData));
        return "match";
    }

    private void addCommon(Model model, OddsService.Snapshot snapshot, String bookmakerText,
                           Integer maxAge, boolean includeLive) {
        model.addAttribute("errors", snapshot.errors());
        model.addAttribute("fetchedAt", snapshot.fetchedAt());
        model.addAttribute("dailyRemaining", snapshot.dailyRemaining());
        model.addAttribute("bookmakers", bookmakerText);
        model.addAttribute("maxAge", maxAge);
        model.addAttribute("includeLive", includeLive);
    }

    /** null = default list from config, "" = all bookmakers. */
    private String bookmakerText(String bookmakers) {
        return bookmakers != null ? bookmakers : String.join(", ", props.bookmakers());
    }

    private static Stream<MatchOdds> filter(Stream<MatchOdds> odds, String bookmakerText, Integer maxAgeMinutes) {
        Set<String> allowed = Arrays.stream(bookmakerText.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        return odds
                .filter(o -> allowed.isEmpty() || allowed.contains(o.bookmaker()))
                .filter(o -> maxAgeMinutes == null || maxAgeMinutes <= 0 || o.ageSeconds() <= maxAgeMinutes * 60L);
    }
}
