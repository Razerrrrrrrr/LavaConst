package com.github.topi314.lavasrc.mirror;

import com.sedmelluq.discord.lavaplayer.track.AudioItem;

import java.util.function.Function;

@FunctionalInterface
public interface MirroringAudioTrackResolver extends Function<MirroringAudioTrack, AudioItem> {

	/**
	 * Called when playing the mirror that was resolved for the given track failed, so a resolver can
	 * forget a mirror that no longer works. Does nothing by default.
	 */
	default void onPlaybackFailed(MirroringAudioTrack track) {
	}
}
