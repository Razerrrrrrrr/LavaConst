package com.github.topi314.lavasrc.mirror;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A small thread-safe LRU cache with a time-to-live, used to remember which mirror was picked for a track.
 */
public final class MirrorCache<V> {

	private static final class Slot<V> {
		final V value;
		final long expiresAtMs;

		Slot(V value, long expiresAtMs) {
			this.value = value;
			this.expiresAtMs = expiresAtMs;
		}
	}

	private final int maxEntries;
	private final long ttlMs;
	private final LinkedHashMap<String, Slot<V>> map;

	public MirrorCache(int maxEntries, long ttlMs) {
		this.maxEntries = maxEntries;
		this.ttlMs = ttlMs;
		this.map = new LinkedHashMap<String, Slot<V>>(16, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, Slot<V>> eldest) {
				return size() > MirrorCache.this.maxEntries;
			}
		};
	}

	public V get(String key) {
		return get(key, System.currentTimeMillis());
	}

	synchronized V get(String key, long nowMs) {
		var entry = map.get(key);
		if (entry == null) {
			return null;
		}
		if (entry.expiresAtMs <= nowMs) {
			map.remove(key);
			return null;
		}
		return entry.value;
	}

	public void put(String key, V value) {
		put(key, value, System.currentTimeMillis());
	}

	synchronized void put(String key, V value, long nowMs) {
		map.put(key, new Slot<>(value, nowMs + ttlMs));
	}

	public synchronized void invalidate(String key) {
		map.remove(key);
	}

	public synchronized int size() {
		return map.size();
	}
}
