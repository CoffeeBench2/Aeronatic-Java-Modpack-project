package com.coffeesaerosmp.auth.cosmetics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** What changed between two keyed snapshots: values to upsert and keys to remove. Pure, unit-tested. */
public record MapDiff<K, V>(List<V> upserts, List<K> removed) {

    public boolean isEmpty() { return upserts.isEmpty() && removed.isEmpty(); }

    public static <K, V> MapDiff<K, V> of(Map<K, V> before, Map<K, V> after) {
        List<V> up = new ArrayList<>();
        List<K> gone = new ArrayList<>();
        for (var e : after.entrySet()) if (!e.getValue().equals(before.get(e.getKey()))) up.add(e.getValue());
        for (K k : before.keySet()) if (!after.containsKey(k)) gone.add(k);
        return new MapDiff<>(up, gone);
    }
}
