package com.github.topi314.lavasrc.mirror;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers mirror providers that keep throwing errors so they can be skipped for a while instead of
 * costing a failed lookup on every track. An empty result is NOT a failure (ISRC lookups often find nothing).
 */
public final class ProviderHealth {

	static final int COOLDOWN_AFTER_FAILURES = 3;
	static final long COOLDOWN_BASE_MS = 30_000L;
	static final long COOLDOWN_MAX_MS = 5 * 60_000L;

	private static final class State {
		volatile int consecutiveFailures;
		volatile long cooldownUntilMs;
	}

	private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

	public boolean isCoolingDown(String provider) {
		return isCoolingDown(provider, System.currentTimeMillis());
	}

	boolean isCoolingDown(String provider, long nowMs) {
		var state = states.get(provider);
		return state != null && state.cooldownUntilMs > nowMs;
	}

	public void recordSuccess(String provider) {
		var state = states.get(provider);
		if (state != null) {
			state.consecutiveFailures = 0;
			state.cooldownUntilMs = 0L;
		}
	}

	public void recordFailure(String provider) {
		recordFailure(provider, System.currentTimeMillis());
	}

	void recordFailure(String provider, long nowMs) {
		var state = states.computeIfAbsent(provider, k -> new State());
		var failures = state.consecutiveFailures + 1;
		state.consecutiveFailures = failures;
		if (failures >= COOLDOWN_AFTER_FAILURES) {
			var exponent = Math.min(failures - COOLDOWN_AFTER_FAILURES, 10);
			var cooldown = Math.min(COOLDOWN_MAX_MS, COOLDOWN_BASE_MS << exponent);
			state.cooldownUntilMs = nowMs + cooldown;
		}
	}
}
