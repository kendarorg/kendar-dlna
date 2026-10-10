package org.kendar.dlna.shared;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/**
 * A location inside a configured library root: root name plus a relative path ({@code ""} = the root itself).
 * The relative path always uses {@code /} separators, is NFC-normalized and contains no empty, {@code .} or
 * {@code ..} segments.
 */
public record RootRef(String root, String rel) implements Comparable<RootRef> {

    public RootRef {
        if (root == null || root.isBlank() || root.indexOf('/') >= 0 || root.indexOf('\\') >= 0
                || root.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid root name: " + root);
        }
        rel = normalizeRel(rel);
    }

    public static RootRef root(String root) {
        return new RootRef(root, "");
    }

    /** Parses {@code root/rel/path} (a plain {@code root} refers to the root itself). */
    public static RootRef parse(String qualified) {
        if (qualified == null) {
            throw new IllegalArgumentException("null path");
        }
        int i = qualified.indexOf('/');
        return i < 0 ? new RootRef(qualified, "") : new RootRef(qualified.substring(0, i), qualified.substring(i + 1));
    }

    static String normalizeRel(String rel) {
        if (rel == null || rel.isEmpty()) {
            return "";
        }
        String s = Normalizer.normalize(rel.replace('\\', '/'), Normalizer.Form.NFC);
        List<String> out = new ArrayList<>();
        for (String seg : s.split("/")) {
            if (seg.isEmpty()) {
                continue;
            }
            if (seg.equals(".") || seg.equals("..") || seg.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Invalid path segment in: " + rel);
            }
            out.add(seg);
        }
        return String.join("/", out);
    }

    public boolean isRoot() {
        return rel.isEmpty();
    }

    /** Last path segment, or the root name for the root itself. */
    public String name() {
        int i = rel.lastIndexOf('/');
        return isRoot() ? root : rel.substring(i + 1);
    }

    public RootRef parent() {
        if (isRoot()) {
            throw new IllegalStateException("Root has no parent: " + root);
        }
        int i = rel.lastIndexOf('/');
        return new RootRef(root, i < 0 ? "" : rel.substring(0, i));
    }

    public RootRef child(String name) {
        if (name == null || name.isEmpty() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Invalid child name: " + name);
        }
        return new RootRef(root, rel.isEmpty() ? name : rel + "/" + name);
    }

    /** {@code root/rel}, the form used for ids and playlist references. */
    public String qualified() {
        return isRoot() ? root : root + "/" + rel;
    }

    @Override
    public int compareTo(RootRef o) {
        int c = root.compareTo(o.root);
        return c != 0 ? c : rel.compareTo(o.rel);
    }

    @Override
    public String toString() {
        return qualified();
    }
}
