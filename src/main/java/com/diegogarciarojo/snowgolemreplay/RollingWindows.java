package com.diegogarciarojo.snowgolemreplay;

import java.util.ArrayList;
import java.util.List;

/** Pure monotonic-time window policy. All access is owned by the module lock. */
final class RollingWindows<T> {
    static final class Window<T> {
        final long start;
        final T value;
        long deadline = Long.MIN_VALUE;
        final List<Incident> incidents = new ArrayList<>();
        Window(long start, T value) { this.start = start; this.value = value; }
        boolean pinned() { return !incidents.isEmpty(); }
    }
    final List<Window<T>> windows = new ArrayList<>();
    void add(long time, T value) { windows.add(new Window<>(time, value)); }

    Window<T> select(long now, long pre) {
        Window<T> selected = null;
        for (var w : windows) {
            if (w.start <= now - pre) selected = w;
        }
        if (selected != null) return selected;
        // Warm-up: preserve all available history, and report the shortfall.
        return windows.stream().findFirst().orElse(null);
    }

    List<Window<T>> retire(long now, long pre) {
        var anchor = select(now, pre);
        var retired = new ArrayList<Window<T>>();
        for (var w : windows) {
            if (w.pinned() ? now >= w.deadline && w != anchor : anchor != null && w.start < anchor.start) retired.add(w);
        }
        windows.removeAll(retired);
        return retired;
    }

    List<Window<T>> drain() {
        var all = new ArrayList<>(windows);
        windows.clear();
        return all;
    }
}
