package app.arbplay.odds.service;

import app.arbplay.odds.client.PropLineClient;
import app.arbplay.odds.client.PropLineParser;
import app.arbplay.odds.config.PropLineProperties;
import app.arbplay.odds.model.MatchOdds;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fetches h2h odds for the configured sports in parallel. Results are cached for
 * propline.cache-seconds, since each sport costs one call of the daily quota.
 */
@Service
public class OddsService {

    private static final Logger log = LoggerFactory.getLogger(OddsService.class);

    private final PropLineClient client;
    private final PropLineParser parser;
    private final PropLineProperties props;
    private final ExecutorService pool = Executors.newFixedThreadPool(8);

    private volatile Snapshot cached;

    public OddsService(PropLineClient client, PropLineParser parser, PropLineProperties props) {
        this.client = client;
        this.parser = parser;
        this.props = props;
    }

    public record Snapshot(List<MatchOdds> odds, List<String> errors, Integer dailyRemaining, Instant fetchedAt) {
    }

    private record SportResult(List<MatchOdds> odds, String error, Integer remaining) {
    }

    /** The odds already loaded, without calling the API (only fetches if nothing was loaded yet). */
    public synchronized Snapshot cachedOnly() {
        if (cached == null) {
            cached = fetchAll();
        }
        return cached;
    }

    public synchronized Snapshot get(boolean forceRefresh) {
        Snapshot current = cached;
        boolean fresh = current != null
                && Duration.between(current.fetchedAt(), Instant.now()).getSeconds() < props.cacheSeconds();
        if (fresh && !forceRefresh) {
            return current;
        }
        cached = fetchAll();
        return cached;
    }

    private Snapshot fetchAll() {
        List<CompletableFuture<SportResult>> futures = props.sports().stream()
                .map(sport -> CompletableFuture.supplyAsync(() -> fetchSport(sport), pool))
                .toList();

        List<MatchOdds> odds = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Integer remaining = null;
        for (CompletableFuture<SportResult> future : futures) {
            SportResult r = future.join();
            odds.addAll(r.odds());
            if (r.error() != null) {
                errors.add(r.error());
            }
            if (r.remaining() != null) {
                remaining = remaining == null ? r.remaining() : Math.min(remaining, r.remaining());
            }
        }
        return new Snapshot(odds, errors, remaining, Instant.now());
    }

    private SportResult fetchSport(String sport) {
        try {
            PropLineClient.OddsResponse response = client.getH2hOdds(sport);
            List<MatchOdds> odds = parser.parse(response.events());
            log.info("{}: {} bookmaker odds", sport, odds.size());
            return new SportResult(odds, null, response.dailyRemaining());
        } catch (Exception e) {
            log.warn("Failed to fetch {}: {}", sport, e.toString());
            return new SportResult(List.of(), sport + ": " + e.getMessage(), null);
        }
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
