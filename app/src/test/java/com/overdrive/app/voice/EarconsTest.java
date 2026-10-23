package com.overdrive.app.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import java.util.Arrays;

import org.junit.Test;

/**
 * The cue fallback used when the selected stream is muted: the OEM voice stream ReplyPlayer uses
 * for "voice" first, then notifications, never the muted channel itself, and never a crash when
 * no AudioManager is at hand.
 */
public class EarconsTest {

    @Test
    public void fallbackOrderPrefersTheOemVoiceStreamThenNotifications() {
        assertEquals(Arrays.asList("voice", "notification"), Earcons.fallbackOrder("media"));
        assertEquals(Arrays.asList("voice", "notification"), Earcons.fallbackOrder("navigation"));
        assertEquals(Arrays.asList("voice", "notification"), Earcons.fallbackOrder(null));
        // The muted channel is never its own fallback.
        assertEquals(Arrays.asList("notification"), Earcons.fallbackOrder("voice"));
        assertEquals(Arrays.asList("voice"), Earcons.fallbackOrder("notification"));
    }

    @Test
    public void fallbackChannelsResolveToDistinctStreams() {
        int media = Earcons.streamFor("media");
        int voice = Earcons.streamFor(Earcons.CHANNEL_VOICE);
        int notification = Earcons.streamFor(Earcons.CHANNEL_NOTIFICATION);
        assertEquals(3, media);          // AudioManager.STREAM_MUSIC
        assertEquals(16, voice);         // the OEM voice stream (same as ReplyPlayer)
        assertEquals(5, notification);   // AudioManager.STREAM_NOTIFICATION
        assertFalse(voice == media);
        assertFalse(notification == media);
        assertFalse(voice == notification);
    }

    @Test
    public void noAudioManagerMeansNoFallback() {
        assertNull(Earcons.audibleFallback(null, "media"));
    }
}
