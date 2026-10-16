package org.kendar.dlna.catalog;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;


/**
 * The configured navigations and extra tags, replaceable at run time (no restart for navigation edits). Readers
 * take a {@link State}; every change bumps its version so the view trees built from the old one are dropped.
 */
public final class NavigationRegistry {

    /** An immutable set of navigations. */
    public record State(List<Navigation> navigations, List<String> extraTags, long version) {
        public State {
            navigations = List.copyOf(navigations);
            extraTags = List.copyOf(extraTags);
        }

        public Optional<Navigation> byName(String name) {
            if (name == null) {
                return Optional.empty();
            }
            return navigations.stream().filter(n -> n.name().equalsIgnoreCase(name.strip())).findFirst();
        }

        public Optional<Navigation> byKey(String key) {
            return navigations.stream().filter(n -> n.key().equals(key)).findFirst();
        }
    }

    private final AtomicReference<State> state;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public NavigationRegistry(List<Navigation> navigations, List<String> extraTags) {
        this.state = new AtomicReference<>(new State(navigations, extraTags, 1));
    }

    /** No navigations. */
    public static NavigationRegistry empty() {
        return new NavigationRegistry(List.of(), List.of());
    }

    private volatile java.util.function.Supplier<java.util.Map<String, Integer>> discovered = java.util.Map::of;

    /** Where the tags found in the library (name and number of files) come from; set by the scanner wiring. */
    public void discovered(java.util.function.Supplier<java.util.Map<String, Integer>> source) {
        this.discovered = source;
    }

    /** The non-standard tags found in the library so far, by name. */
    public java.util.Map<String, Integer> discovered() {
        return discovered.get();
    }

    public State state() {
        return state.get();
    }

    /** Replaces the navigations (and the extra tags) and tells the listeners. */
    public synchronized State set(List<Navigation> navigations, List<String> extraTags) {
        State next = new State(navigations, extraTags, state.get().version() + 1);
        state.set(next);
        listeners.forEach(Runnable::run);
        return next;
    }

    /** Called after every {@link #set}. */
    public void onChange(Runnable listener) {
        listeners.add(listener);
    }
}
