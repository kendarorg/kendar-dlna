package org.kendar.dlna.lyrics;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Several providers ({@code lyrics.provider: "lrclib,lrc.cx,lyrics.ovh"}) asked at the same time, so a slow or busy one
 * does not hold the others up. Everything they find comes back, so the person can choose: time-synced lyrics first,
 * then the plain ones, each kind in the order of the list, the same text from two providers only once. A provider that
 * fails does not stop the others; the failure is reported only when nobody found anything.
 */
public final class ChainProvider implements LyricsProvider {

    private final List<LyricsProvider> providers;

    public ChainProvider(List<LyricsProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    @Override
    public String id() {
        return String.join(",", providers.stream().map(LyricsProvider::id).toList());
    }

    @Override
    public List<String> ids() {
        return providers.stream().map(LyricsProvider::id).toList();
    }

    @Override
    public List<Lyrics> find(Query query) throws IOException {
        return find(query, (id, found, error) -> { });
    }

    @Override
    public List<Lyrics> find(Query query, Listener listener) throws IOException {
        List<Lyrics> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        IOException failure = null;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<Lyrics>>> running = new ArrayList<>();
            for (LyricsProvider p : providers) {
                running.add(pool.submit(() -> {
                    try {
                        List<Lyrics> found = p.find(query);
                        listener.answered(p.id(), found, null);   // told the moment it answers
                        return found;
                    } catch (IOException e) {
                        listener.answered(p.id(), List.of(), e.getMessage());
                        throw e;
                    }
                }));
            }
            for (int i = 0; i < running.size(); i++) {
                try {
                    for (Lyrics l : running.get(i).get()) {
                        if (seen.add(l.text().strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "))) {
                            out.add(l);
                        }
                    }
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (failure == null) {
                        failure = new IOException(providers.get(i).id() + ": " + cause.getMessage(), cause);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    running.forEach(f -> f.cancel(true));
                    throw new IOException("interrupted", e);
                }
            }
        }
        if (out.isEmpty() && failure != null) {
            throw failure;
        }
        out.sort((a, b) -> Boolean.compare(b.synced(), a.synced()));   // stable: list order kept inside each kind
        return out;
    }
}
