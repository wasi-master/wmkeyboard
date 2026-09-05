package com.wasimaster.wmkeyboard.core.settings

/**
 * Media-control tool settings, grouped rather than flat for the reason every
 * other family here is: [KeyboardSettings] lives near the JVM's 255-argument
 * `copy$default` ceiling, so a new feature buys one slot and puts everything
 * inside it. The DataStore keys stay flat (`media_pin_*`, `media_music_apps`),
 * so backup and locked-settings handling need no change.
 */
data class MediaControlSettings(
    /**
     * Put the media tool on the toolbar by itself while music is playing, so
     * the transport is one tap away without giving the tool a permanent slot.
     *
     * The pin is transient: it is never written to [KeyboardSettings.toolbarTools],
     * it appends after the tools the user actually pinned, and it does nothing
     * when they have already pinned the tool themselves.
     */
    val pinWhilePlaying: Boolean = true,
    /**
     * Packages whose playback counts as "music" for that pin. An app outside
     * this set can still be driven from the panel — the set only decides what
     * is worth taking a toolbar slot for, which is why a video app or a game
     * holding a media session does not pin anything.
     *
     * Seeded with [DefaultMusicApps]. An empty set means nothing ever pins,
     * which is a legitimate thing to want and is why this is stored as a set
     * rather than "empty means all".
     */
    val musicApps: Set<String> = DefaultMusicApps,
)

/**
 * The players ticked on a fresh install: streaming services, offline players,
 * and the podcast and audiobook apps people listen to the same way.
 *
 * A guess, and knowingly an incomplete one — no list of package names survives
 * contact with every music app on earth. It exists so the feature works out of
 * the box for most people; the picker behind it lists every installed app and
 * leads with the ones that declare a media browser service, which is how a
 * player this list never heard of gets ticked in two taps.
 *
 * Video apps are deliberately absent. YouTube, a browser and a game all hold
 * media sessions, and pinning a music transport because a video started is the
 * behaviour this allowlist exists to prevent.
 */
val DefaultMusicApps: Set<String> = setOf(
    // Streaming
    "com.spotify.music",
    "com.spotify.lite",
    "com.google.android.apps.youtube.music",
    "com.apple.android.music",
    "com.amazon.mp3",
    "deezer.android.app",
    "com.aspiro.tidal",
    "com.soundcloud.android",
    "com.pandora.android",
    "com.bandcamp.android",
    "com.anghami",
    "com.gaana",
    "com.jio.media.jiobeats",
    "com.bsbportal.music",
    "com.clearchannel.iheartradio.controller",
    "com.zvooq.openplay",
    "ru.yandex.music",
    // Offline players
    "com.maxmpz.audioplayer",
    "org.videolan.vlc",
    "com.aimp.player",
    "com.tbig.playerpro",
    "com.jetappfactory.jetaudio",
    "code.name.monkey.retromusic",
    "com.simplemobiletools.musicplayer",
    "org.oxycblt.auxio",
    "com.piyush.music",
    "com.mardous.booming",
    "com.awedea.nyx",
    "com.doubleTwist.androidPlayer",
    "org.gateshipone.odyssey",
    "io.github.muntashirakon.Music",
    "com.kapp.youtube.final",
    "com.malopieds.innertune",
    "com.zionhuang.music",
    // Self-hosted clients
    "org.jellyfin.mobile",
    "com.cappielloantonio.tempo",
    "github.daneren2005.dsub",
    // Podcasts and audiobooks
    "au.com.shiftyjelly.pocketcasts",
    "com.google.android.apps.podcasts",
    "com.bambuna.podcastaddict",
    "de.danoeh.antennapod",
    "com.audible.application",
    "com.acmeandroid.listen",
)
