package com.github.topi314.lavasrc.mirror;

import com.github.topi314.lavasrc.applemusic.AppleMusicSourceManager;
import com.github.topi314.lavasrc.spotify.SpotifySourceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Locale;

public class DefaultMirroringAudioTrackResolver implements MirroringAudioTrackResolver {

	private static final Logger log = LoggerFactory.getLogger(DefaultMirroringAudioTrackResolver.class);

	/** How many search results are considered when picking the best mirror. */
	private static final int MAX_RANKED_RESULTS = 10;
	private static final int CACHE_MAX_ENTRIES = 5000;
	private static final long CACHE_TTL_MS = 6 * 60 * 60 * 1000L;

	private String[] providers = {
		"ytsearch:\"" + MirroringAudioSourceManager.ISRC_PATTERN + "\"",
		"ytsearch:" + MirroringAudioSourceManager.QUERY_PATTERN
	};

	private final MirrorCache<AudioTrack> cache = new MirrorCache<>(CACHE_MAX_ENTRIES, CACHE_TTL_MS);
	private final ProviderHealth providerHealth = new ProviderHealth();

	public DefaultMirroringAudioTrackResolver(String[] providers) {
		if (providers != null && providers.length > 0) {
			this.providers = providers;
		}
	}

	@Override
	public AudioItem apply(MirroringAudioTrack mirroringAudioTrack) {
		var startNanos = System.nanoTime();
		var info = mirroringAudioTrack.getInfo();
		var cacheKey = getCacheKey(mirroringAudioTrack);

		var cached = cache.get(cacheKey);
		if (cached != null) {
			var clone = cached.makeClone();
			if (clone instanceof InternalAudioTrack) {
				log.info("Mirror for \"{}\" served from cache ({}) in {}ms", info.title, cached.getIdentifier(), elapsedMs(startNanos));
				return clone;
			}
			cache.invalidate(cacheKey);
		}

		var hasIsrc = info.isrc != null && !info.isrc.isEmpty();
		var applicable = new ArrayList<String>();
		for (var provider : providers) {
			if (provider.startsWith(SpotifySourceManager.SEARCH_PREFIX)) {
				log.warn("Can not use spotify search as search provider!");
				continue;
			}

			if (provider.startsWith(AppleMusicSourceManager.SEARCH_PREFIX)) {
				log.warn("Can not use apple music search as search provider!");
				continue;
			}

			if (provider.contains(MirroringAudioSourceManager.ISRC_PATTERN) && !hasIsrc) {
				log.debug("Ignoring identifier \"{}\" because this track does not have an ISRC!", provider);
				continue;
			}

			applicable.add(provider);
		}

		// If every provider is cooling down, try them all anyway rather than failing outright.
		var ignoreCooldowns = true;
		for (var provider : applicable) {
			if (!providerHealth.isCoolingDown(provider)) {
				ignoreCooldowns = false;
				break;
			}
		}

		for (var template : applicable) {
			if (!ignoreCooldowns && providerHealth.isCoolingDown(template)) {
				log.debug("Skipping mirror provider \"{}\" while it is cooling down", template);
				continue;
			}

			var query = template;
			if (query.contains(MirroringAudioSourceManager.ISRC_PATTERN)) {
				query = query.replace(MirroringAudioSourceManager.ISRC_PATTERN, info.isrc.replace("-", ""));
			}
			query = query.replace(MirroringAudioSourceManager.QUERY_PATTERN, getTrackTitle(mirroringAudioTrack));

			AudioItem item;
			try {
				item = mirroringAudioTrack.loadItem(query);
			} catch (Exception e) {
				providerHealth.recordFailure(template);
				log.warn("Mirror provider \"{}\" failed: {}", query, e.getMessage());
				continue;
			}
			providerHealth.recordSuccess(template);

			if (item == AudioReference.NO_TRACK) {
				continue;
			}

			if (item instanceof AudioPlaylist) {
				var tracks = ((AudioPlaylist) item).getTracks();
				// If the track is an empty playlist, skip the provider
				if (tracks.isEmpty()) {
					continue;
				}

				var candidates = tracks.subList(0, Math.min(tracks.size(), MAX_RANKED_RESULTS));
				var index = MirrorRanker.bestIndex(candidates, candidate -> {
					var c = candidate.getInfo();
					return MirrorRanker.score(info.title, info.author, info.length, c.title, c.author, c.length, c.isStream);
				});
				var best = candidates.get(index);
				remember(cacheKey, best);
				log.info("Mirrored \"{}\" via \"{}\" -> {} (result {} of {}) in {}ms", info.title, query, best.getIdentifier(), index + 1, candidates.size(), elapsedMs(startNanos));
				return best;
			}

			if (item instanceof AudioTrack) {
				remember(cacheKey, (AudioTrack) item);
				log.info("Mirrored \"{}\" via \"{}\" -> {} in {}ms", info.title, query, ((AudioTrack) item).getIdentifier(), elapsedMs(startNanos));
			}
			return item;
		}

		log.info("No mirror found for \"{}\" after {}ms", info.title, elapsedMs(startNanos));
		return AudioReference.NO_TRACK;
	}

	@Override
	public void onPlaybackFailed(MirroringAudioTrack track) {
		cache.invalidate(getCacheKey(track));
		log.info("Forgot cached mirror for \"{}\" after a playback failure", track.getInfo().title);
	}

	public String getTrackTitle(MirroringAudioTrack mirroringAudioTrack) {
		var query = mirroringAudioTrack.getInfo().title;
		if (!mirroringAudioTrack.getInfo().author.equals("unknown")) {
			query += " " + mirroringAudioTrack.getInfo().author;
		}
		return query;
	}

	private void remember(String cacheKey, AudioTrack track) {
		if (track instanceof InternalAudioTrack) {
			// Store a clone so the instance that is about to be played is never shared with the cache.
			cache.put(cacheKey, track.makeClone());
		}
	}

	private String getCacheKey(MirroringAudioTrack track) {
		var info = track.getInfo();
		if (info.isrc != null && !info.isrc.isEmpty()) {
			return "isrc:" + info.isrc.replace("-", "").toUpperCase(Locale.ROOT);
		}
		return "q:" + getTrackTitle(track).toLowerCase(Locale.ROOT) + "|" + (info.length / 1000);
	}

	private static long elapsedMs(long startNanos) {
		return (System.nanoTime() - startNanos) / 1_000_000L;
	}

}
