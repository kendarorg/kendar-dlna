package org.kendar.dlna.curation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.catalog.TrackTags;
import org.kendar.dlna.shared.Json;
import org.kendar.dlna.shared.RootRef;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One tag change applied to every whole-file track of a directory and its sub-directories, as a background job.
 * A preview counts what would change; the apply call needs the exact consent phrase and the previewed count, so a
 * library that changed in between is refused. The old values of every changed file go to a change log. Cue/chapter
 * segments have no tags of their own and are skipped (and counted); a file that cannot be written fails alone.
 */
public final class BulkTags {

    private static final Logger LOG = Logger.getLogger(BulkTags.class.getName());
    static final int CHUNK = 100;
    static final int MAX_ERRORS = 200;

    /** What an apply would touch. {@code files} = tracks that will be written, the rest are skipped and why. */
    public record Preview(int files, int segments, int notEditable, int notApplicable) {
    }

    /** One failed file. */
    public record Failure(String path, String error) {
    }

    /** Job state; a finished job stays readable until the next one starts. */
    public record Status(boolean running, String directory, int total, int done, int failed, List<Failure> failures,
            Instant startedAt, Instant finishedAt, String error) {
    }

    private final TagService tags;
    private final Supplier<Snapshot> catalog;
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bulk-tags");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Path changeLog;
    private volatile Status status = new Status(false, null, 0, 0, 0, List.of(), null, null, null);

    BulkTags(TagService tags, Supplier<Snapshot> catalog) {
        this.tags = tags;
        this.catalog = catalog;
    }

    /** Where the old values of changed files are appended (JSON lines); null = not logged. */
    public void changeLog(Path file) {
        this.changeLog = file;
    }

    public Status status() {
        return status;
    }

    public Preview preview(RootRef dir, TagEdit edit) {
        Plan plan = plan(dir, edit);
        return new Preview(plan.refs.size(), plan.segments, plan.notEditable, plan.notApplicable);
    }

    /**
     * Starts the job. {@code expectedFiles} is the count of the preview the user confirmed.
     *
     * @throws CurationException INVALID without consent or edit, CONFLICT when the count changed or a job runs
     */
    public Status start(RootRef dir, TagEdit edit, String confirm, int expectedFiles) {
        Consent.require(confirm);
        Plan plan = plan(dir, edit);
        if (plan.refs.size() != expectedFiles) {
            throw new CurationException(CurationException.CONFLICT, "The library changed since the preview: "
                    + plan.refs.size() + " files would be edited now, not " + expectedFiles + "; preview again");
        }
        if (plan.refs.isEmpty()) {
            throw new CurationException(CurationException.INVALID, "No file would be changed");
        }
        if (!running.compareAndSet(false, true)) {
            throw new CurationException(CurationException.CONFLICT, "A bulk tag edit is already running");
        }
        Instant started = Instant.now();
        status = new Status(true, dir.qualified(), plan.refs.size(), 0, 0, List.of(), started, null, null);
        pool.execute(() -> run(dir, edit, plan.refs, started));
        return status;
    }

    private void run(RootRef dir, TagEdit edit, List<RootRef> refs, Instant started) {
        List<Failure> failures = new ArrayList<>();
        int done = 0;
        int failed = 0;
        String fatal = null;
        try {
            for (int from = 0; from < refs.size(); from += CHUNK) {
                List<RootRef> chunk = refs.subList(from, Math.min(refs.size(), from + CHUNK));
                Map<RootRef, TrackTags> before = new LinkedHashMap<>();
                Snapshot snap = catalog.get();
                for (RootRef r : chunk) {
                    snap.track(r).ifPresent(t -> before.put(r, t.tags()));
                }
                List<TagService.Result> results = tags.write(chunk, edit);
                List<RootRef> changed = new ArrayList<>();
                for (TagService.Result r : results) {
                    if (r.ok()) {
                        done++;
                        changed.add(r.ref());
                    } else {
                        failed++;
                        if (failures.size() < MAX_ERRORS) {
                            failures.add(new Failure(r.ref().qualified(), r.error()));
                        }
                    }
                }
                log(changed, before, edit);
                status = new Status(true, dir.qualified(), refs.size(), done, failed, List.copyOf(failures),
                        started, null, null);
            }
        } catch (RuntimeException e) {
            LOG.warning("Bulk tag edit failed: " + e);
            fatal = e.getMessage() == null ? e.toString() : e.getMessage();
        } finally {
            status = new Status(false, dir.qualified(), refs.size(), done, failed, List.copyOf(failures), started,
                    Instant.now(), fatal);
            running.set(false);
        }
    }

    /** Appends one JSON line per changed file with the values the edit replaced. */
    private void log(List<RootRef> changed, Map<RootRef, TrackTags> before, TagEdit edit) {
        Path file = changeLog;
        if (file == null || changed.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        String now = Instant.now().toString();
        for (RootRef r : changed) {
            TrackTags old = before.get(r);
            if (old == null) {
                continue;
            }
            ObjectNode line = Json.mapper().createObjectNode();
            line.put("at", now);
            line.put("path", r.qualified());
            ObjectNode was = line.putObject("was");
            ObjectNode now2 = line.putObject("now");
            put(was, now2, "title", edit.title(), old.title());
            put(was, now2, "artist", edit.artist(), old.artist());
            put(was, now2, "album", edit.album(), old.album());
            put(was, now2, "albumArtist", edit.albumArtist(), old.albumArtist());
            put(was, now2, "genre", edit.genre(), old.genre());
            put(was, now2, "year", edit.year(), old.year() == null ? null : String.valueOf(old.year()));
            put(was, now2, "trackNo", edit.trackNo(), old.trackNo() == null ? null : String.valueOf(old.trackNo()));
            put(was, now2, "discNo", edit.discNo(), old.discNo() == null ? null : String.valueOf(old.discNo()));
            put(was, now2, "discTotal", edit.discTotal(), old.discTotal() == null ? null : String.valueOf(old.discTotal()));
            put(was, now2, "composer", edit.composer(), old.composer());
            sb.append(line).append('\n');
        }
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.warning("Cannot write the tag change log " + file + ": " + e);
        }
    }

    private static void put(ObjectNode was, ObjectNode now, String field, String edited, String old) {
        if (edited != null) {
            was.put(field, old);
            now.put(field, edited);
        }
    }

    private record Plan(List<RootRef> refs, int segments, int notEditable, int notApplicable) {
    }

    private Plan plan(RootRef dir, TagEdit edit) {
        if (edit == null || edit.isEmpty()) {
            throw new CurationException(CurationException.INVALID, "Nothing to change");
        }
        List<String> problems = edit.problems();
        if (!problems.isEmpty()) {
            throw new CurationException(CurationException.INVALID, String.join("; ", problems), problems);
        }
        Snapshot snap = catalog.get();
        if (snap.folder(dir).isEmpty()) {
            throw new CurationException(CurationException.NOT_FOUND, "Unknown directory: " + dir);
        }
        List<RootRef> refs = new ArrayList<>();
        int segments = 0;
        int notEditable = 0;
        int notApplicable = 0;
        for (Track t : snap.subtreeTracks(dir)) {
            if (t.isSegment()) {
                segments++;
            } else if (!t.format().tagsEditable()) {
                notEditable++;
            } else if (edit.restrictedTo(t.format().editableFields()).isEmpty()) {
                notApplicable++;
            } else {
                refs.add(t.ref());
            }
        }
        return new Plan(refs, segments, notEditable, notApplicable);
    }
}
