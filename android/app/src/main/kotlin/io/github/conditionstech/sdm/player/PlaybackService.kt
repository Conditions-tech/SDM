package io.github.conditionstech.sdm.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.github.conditionstech.sdm.MainActivity
import io.github.conditionstech.sdm.SdmApplication

/**
 * Plays music with the screen off (M17b, ADR-0025). Media3 shows the notification and the
 * lock-screen controls; headphone buttons and other apps' sound (calls, navigation) are handled
 * by the player's audio focus. Unplugging headphones pauses, as people expect.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    // pauseAtEndOfMediaItems (the sleep timer's "end of this song") is marked unstable in Media3:
    // it may change between versions, so updating Media3 means re-checking that choice.
    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true, // handle audio focus: pause for calls, duck for navigation
            )
            .setHandleAudioBecomingNoisy(true) // headphones unplugged: pause
            .build()
        val timer = (application as SdmApplication).sleepTimer
        timer.target = object : SleepTarget {
            override fun setVolume(volume: Float) {
                player.volume = volume
            }

            override fun pause() = player.pause()

            override fun pauseAtEndOfSong(enabled: Boolean) {
                player.pauseAtEndOfMediaItems = enabled
            }
        }
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) timer.songEnded()
            }
        })
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        session = MediaSession.Builder(this, player).setSessionActivity(openApp).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiping SDM away stops the service only when nothing is playing. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        (application as SdmApplication).sleepTimer.let { if (it.target != null) it.target = null }
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
