package dev.wander.android.opentagviewer.service;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;

/**
 * Plays the left-behind alarm, on repeat, until somebody deals with it.
 *
 * <p><b>Why the sound is not on the notification channel.</b> A channel's sound is fixed at the
 * moment it is created and cannot be changed afterwards - setting it again in code does nothing
 * for anyone who already has the channel. A sound the user picks has to be changeable, so the
 * channel is left silent and the audio is played here instead. That buys the repeat as well: a
 * channel plays its sound once, which is a chime, and a chime from a pocket during a walk is
 * exactly the thing that gets missed.
 *
 * <p><b>Alarm usage, deliberately.</b> Notification audio plays at notification volume, which on
 * a phone that has been quietened is nothing at all. This is the one notification in the app
 * allowed to interrupt, so it goes out the way an alarm clock does and stays audible with the
 * ringer down.
 *
 * <p><b>Once, briefly, and then it is over.</b> Alarm usage already solves being heard; making
 * the sound repeat as well was a second answer to the same question, and it turned the alert
 * into something that had to be switched off. That is a worse problem than the one it solved.
 * Tapping the notification dismissed it through {@code setAutoCancel} without firing the delete
 * intent, so the obvious reaction to an alarm removed the only control over it, and a tag at the
 * edge of range re-triggered before the cap could ever run out.
 *
 * <p>So it plays once and is cut off at {@link #MAX_DURATION_MS} regardless of how long the
 * chosen ringtone runs. Nothing here needs stopping, which is why nothing offers to.
 */
public final class LeftBehindAlarm {
    private static final String TAG = LeftBehindAlarm.class.getSimpleName();

    /**
     * How long the sound is allowed to run.
     *
     * <p>Long enough to be noticed through a coat, short enough that it is over before anybody
     * reaches for the phone. The cap exists because the sound is the user's to choose and some
     * alarm ringtones run for half a minute: without it, "plays once" would mean something
     * different for every choice. The notification stays either way; the sound is what is
     * time-limited, not the message.
     */
    static final long MAX_DURATION_MS = 5_000L;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Nullable
    private MediaPlayer player;

    public LeftBehindAlarm(final Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Sounds the alarm once, replacing one already sounding.
     *
     * <p>Replacing rather than layering: two tags left behind at once is one situation, and two
     * alarm sounds over each other is just noise. Each still gets its own notification.
     *
     * @param soundUri what the user picked, or null/empty for the system's default alarm.
     */
    public void start(@Nullable final String soundUri) {
        this.stop();

        final Uri sound = resolve(soundUri);
        if (sound == null) {
            Log.w(TAG, "No alarm sound available; the notification will be silent");
            return;
        }

        try {
            final MediaPlayer started = new MediaPlayer();
            started.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            started.setDataSource(this.context, sound);
            started.setLooping(false);
            started.prepare();
            started.start();

            this.player = started;
            this.handler.postDelayed(this::stop, MAX_DURATION_MS);

            Log.i(TAG, "Left-behind alarm sounding");
        } catch (final Exception couldNotPlay) {
            // A sound that has been deleted, a volume that is not mounted, an audio focus the
            // system refused. None of it is worth failing the alert over: the notification is
            // already posted and is the part that carries the information.
            Log.w(TAG, "Could not play the left-behind alarm", couldNotPlay);
            this.stop();
        }
    }

    /** Silences the alarm. Safe to call when nothing is playing. */
    public void stop() {
        this.handler.removeCallbacksAndMessages(null);

        final MediaPlayer sounding = this.player;
        this.player = null;

        if (sounding == null) {
            return;
        }

        try {
            if (sounding.isPlaying()) {
                sounding.stop();
            }
        } catch (final IllegalStateException alreadyGone) {
            Log.d(TAG, "Alarm player was already finished", alreadyGone);
        } finally {
            sounding.release();
        }
    }

    /**
     * The user's choice, or the system default alarm when there is none or it cannot be read.
     *
     * <p>Falls back twice: an alarm sound the device does not have is answered with the
     * notification sound rather than with silence, because this is the one alert where being
     * quiet is the failure.
     */
    @Nullable
    private static Uri resolve(@Nullable final String soundUri) {
        if (!TextUtils.isEmpty(soundUri)) {
            return Uri.parse(soundUri);
        }

        final Uri alarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        return alarm != null
                ? alarm
                : RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
    }

    /** Whether the phone's alarm stream is turned all the way down. */
    public boolean isAlarmStreamSilent() {
        final AudioManager audio = this.context.getSystemService(AudioManager.class);
        return audio != null && audio.getStreamVolume(AudioManager.STREAM_ALARM) == 0;
    }
}
