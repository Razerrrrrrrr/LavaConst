package com.github.topi314.lavasrc.mirror;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;

/**
 * Scores search results when mirroring a track (e.g. a Spotify track played via YouTube), so the
 * best match is picked instead of blindly taking the first result.
 *
 * <p>The ranking is deliberately conservative: it only moves away from the first result when another
 * result is clearly better (see {@link #MIN_MARGIN}).
 */
public final class MirrorRanker {

	/** A later result must beat the first one by at least this many points to be picked instead. */
	public static final int MIN_MARGIN = 10;

	/** Markers of a different version of a song. Penalised only if the wanted track does not contain them itself. */
	private static final String[] UNWANTED_WORDS = {
		"live", "remix", "sped up", "speed up", "slowed", "reverb", "nightcore", "8d", "cover",
		"karaoke", "instrumental", "acoustic", "mashup", "bass boosted", "reaction", "tutorial"
	};

	private static final Pattern[] UNWANTED = new Pattern[UNWANTED_WORDS.length];

	static {
		for (int i = 0; i < UNWANTED_WORDS.length; i++) {
			UNWANTED[i] = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(UNWANTED_WORDS[i]) + "(?![\\p{L}\\p{N}])");
		}
	}

	private static final Pattern BRACKETED = Pattern.compile("[\\(\\[][^\\)\\]]*[\\)\\]]");
	private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

	private MirrorRanker() {
	}

	public static int score(String wantedTitle, String wantedAuthor, long wantedLengthMs,
	                        String candTitle, String candAuthor, long candLengthMs, boolean candIsStream) {
		var wantedAuthorClean = "unknown".equalsIgnoreCase(wantedAuthor) ? "" : lower(wantedAuthor);
		var wantedAll = lower(wantedTitle) + " " + wantedAuthorClean;
		var candTitleLower = lower(candTitle);
		var candAuthorLower = lower(candAuthor);
		var haystack = tokens(candTitleLower + " " + candAuthorLower);

		var score = 0;

		if (candIsStream) {
			score -= 200;
		}

		if (wantedLengthMs > 0 && candLengthMs > 0 && candLengthMs != Long.MAX_VALUE) {
			var diff = Math.abs(wantedLengthMs - candLengthMs);
			if (diff <= 2_500) {
				score += 40;
			} else if (diff <= 7_000) {
				score += 20;
			} else if (diff > 60_000) {
				score -= 120;
			} else if (diff > 15_000) {
				score -= 60;
			}
		}

		for (var pattern : UNWANTED) {
			if (pattern.matcher(candTitleLower).find() && !pattern.matcher(wantedAll).find()) {
				score -= 50;
			}
		}

		if (candAuthorLower.endsWith("- topic")) {
			score += 15;
		}
		if (candTitleLower.contains("official audio")) {
			score += 10;
		}

		score += (int) Math.round(30 * overlap(tokens(BRACKETED.matcher(lower(wantedTitle)).replaceAll(" ")), haystack));
		score += (int) Math.round(15 * overlap(tokens(wantedAuthorClean), haystack));
		return score;
	}

	/**
	 * @return the index of the best item. Returns 0 unless another item beats the first by {@link #MIN_MARGIN}.
	 */
	public static <T> int bestIndex(List<T> items, ToIntFunction<T> scorer) {
		if (items.size() <= 1) {
			return 0;
		}
		var firstScore = scorer.applyAsInt(items.get(0));
		var bestIndex = 0;
		var bestScore = firstScore;
		for (var i = 1; i < items.size(); i++) {
			var s = scorer.applyAsInt(items.get(i));
			if (s > bestScore) {
				bestScore = s;
				bestIndex = i;
			}
		}
		return bestScore - firstScore >= MIN_MARGIN ? bestIndex : 0;
	}

	private static String lower(String s) {
		return s == null ? "" : s.toLowerCase(Locale.ROOT);
	}

	private static Set<String> tokens(String s) {
		var set = new HashSet<String>();
		for (var token : NON_WORD.split(lower(s))) {
			if (!token.isEmpty()) {
				set.add(token);
			}
		}
		return set;
	}

	/** Fraction of {@code wanted} tokens present in {@code haystack}; 0 when there is nothing to compare. */
	private static double overlap(Set<String> wanted, Set<String> haystack) {
		if (wanted.isEmpty()) {
			return 0;
		}
		var hits = 0;
		for (var token : wanted) {
			if (haystack.contains(token)) {
				hits++;
			}
		}
		return (double) hits / wanted.size();
	}
}
