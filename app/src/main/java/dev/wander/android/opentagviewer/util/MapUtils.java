package dev.wander.android.opentagviewer.util;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class MapUtils {
    public static <K, V> Map<K, List<V>> toListOfOne(final Map<K, V> mapToOne) {
        return mapToOne.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, kvp -> List.of(kvp.getValue())));
    }
}
