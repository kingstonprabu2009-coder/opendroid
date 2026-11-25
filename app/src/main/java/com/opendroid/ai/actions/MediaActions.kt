package com.opendroid.ai.actions

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import androidx.core.net.toUri
import com.opendroid.ai.actions.base.Action
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.util.DeviceCapabilities
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaActions @Inject constructor(
    private val playbackVerifier: MediaPlaybackVerifier
) {

    fun getActions(): List<Action> = listOf(
        PlayMusicAction(playbackVerifier),
        PauseMusicAction(),
        ResumeMusicAction(),
        NextTrackAction(),
        PrevTrackAction(),
        SetVolumeMusicAction(),
        PlayYoutubeAction(playbackVerifier),
        TakePhotoAction(),
        RecordVideoAction()
    )

    private class PlayMusicAction(
        private val playbackVerifier: MediaPlaybackVerifier
    ) : Action {
        override val name: String = "PLAY_MUSIC"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val query = params["query"] ?: ""
            val app = params["app"] ?: "spotify"
            return try {
                val normalizedApp = app.lowercase()
                if (normalizedApp !in setOf("spotify", "youtube", "local")) {
                    return ActionResult(false, null, "That music app is not supported.")
                }
                val encQuery = URLEncoder.encode(query, "UTF-8")
                val intent = when (normalizedApp) {
                    "spotify" -> {
                        Intent(Intent.ACTION_VIEW, "spotify:search:$encQuery".toUri()).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    }
                    "youtube" -> {
                        Intent(Intent.ACTION_VIEW, "https://music.youtube.com/search?q=$encQuery".toUri()).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    }
                    else -> {
                        Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
                            putExtra(MediaStore.EXTRA_MEDIA_TITLE, query)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    }
                }
                if (intent.resolveActivity(context.packageManager) == null) {
                    return ActionResult(false, null, "The requested music app isn't available on this device.")
                }
                context.startActivity(intent)
                if (playbackVerifier.awaitVerifiedPlayback(context, normalizedApp, query)) {
                    ActionResult(true, if (query.isNotEmpty()) "Playing '$query' for you!" else "Music is playing!", null)
                } else {
                    ActionResult(false, null, "The music app opened, but playback could not be verified.")
                }
            } catch (e: Exception) {
                Log.e("PlayMusic", "Music action failed: ${e.javaClass.simpleName}")
                ActionResult(false, null, "Couldn't play that right now. Try again?")
            }
        }
    }

    private class PauseMusicAction : Action {
        override val name: String = "PAUSE_MUSIC"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            return try {
                sendMediaKeyEvent(context, KeyEvent.KEYCODE_MEDIA_PAUSE)
                ActionResult(true, "Music paused!", null)
            } catch (e: Exception) {
                Log.e("PauseMusic", "Pause failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't pause the music.")
            }
        }
    }

    private class ResumeMusicAction : Action {
        override val name: String = "RESUME_MUSIC"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            return try {
                sendMediaKeyEvent(context, KeyEvent.KEYCODE_MEDIA_PLAY)
                ActionResult(true, "Music resumed!", null)
            } catch (e: Exception) {
                Log.e("ResumeMusic", "Resume failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't resume the music.")
            }
        }
    }

    private class NextTrackAction : Action {
        override val name: String = "NEXT_TRACK"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            return try {
                sendMediaKeyEvent(context, KeyEvent.KEYCODE_MEDIA_NEXT)
                ActionResult(true, "Skipped to the next song!", null)
            } catch (e: Exception) {
                Log.e("NextTrack", "Skip failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't skip the track.")
            }
        }
    }

    private class PrevTrackAction : Action {
        override val name: String = "PREV_TRACK"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            return try {
                sendMediaKeyEvent(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                ActionResult(true, "Going back to the previous song!", null)
            } catch (e: Exception) {
                Log.e("PrevTrack", "Back failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't go back.")
            }
        }
    }

    private class SetVolumeMusicAction : Action {
        override val name: String = "SET_VOLUME_MUSIC"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val level = params["level"]?.toIntOrNull() ?: 50
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return try {
                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val targetVolume = (level * maxVolume) / 100
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, AudioManager.FLAG_SHOW_UI)
                ActionResult(true, "Music volume is at $level% now.", null)
            } catch (e: Exception) {
                Log.e("SetVolumeMusic", "Volume failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't change the music volume.")
            }
        }
    }

    private class PlayYoutubeAction(
        private val playbackVerifier: MediaPlaybackVerifier
    ) : Action {
        override val name: String = "PLAY_YOUTUBE"

        private data class Plan(val query: String, val subscribe: Boolean, val like: Boolean, val comment: String)

        private val fillers = setOf("pannu", "panni", "pannunga", "pannuga", "podu", "potu", "podunga", "seiyum", "seyyum",
            "it", "also", "and", "then", "that", "as", "the", "channel", "video", "a", "please", "ku", "la")

        /** Splits "song name and subscribe and comment nice song" into the search text plus extras. */
        private fun plan(params: Map<String, String>): Plan {
            val raw = (params["query"] ?: "").trim()
            fun flag(k: String) = params[k]?.trim()?.lowercase() in setOf("true", "yes", "1")
            var subscribe = flag("subscribe")
            var like = flag("like")
            var comment = params["comment"]?.trim().orEmpty()
            var query = raw
            val cut = Regex("(?:\\s+(?:and|then|also)\\s+|,\\s*|\\s+)(subscribe|comment)\\b|\\s+(?:and|then|also)\\s+like\\b", RegexOption.IGNORE_CASE)
                .find(raw)
            if (cut != null) {
                query = raw.substring(0, cut.range.first).trim()
                val tail = raw.substring(cut.range.first)
                if (Regex("\\bsubscribe\\b", RegexOption.IGNORE_CASE).containsMatchIn(tail)) subscribe = true
                if (Regex("\\blike\\b", RegexOption.IGNORE_CASE).containsMatchIn(tail)) like = true
                val cm = Regex("\\bcomment\\b[:\\s]*(.*)$", RegexOption.IGNORE_CASE).find(tail)
                if (cm != null && comment.isBlank()) {
                    val quoted = Regex("[\"\u201c']([^\"\u201d']+)[\"\u201d']").find(cm.groupValues[1])?.groupValues?.get(1)
                    val rest = quoted ?: cm.groupValues[1]
                        .replace(Regex("\\b(and|then)\\s+(subscribe|like)\\b.*$", RegexOption.IGNORE_CASE), "")
                        .split(Regex("\\s+")).filter { it.isNotBlank() && it.lowercase() !in fillers }
                        .joinToString(" ")
                    comment = rest.ifBlank { "Super video!" }
                }
            }
            return Plan(query, subscribe, like, comment)
        }

        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val plan = plan(params)
            val query = plan.query
            return try {
                val encQuery = URLEncoder.encode(query, "UTF-8")
                val uri = "https://www.youtube.com/results?search_query=$encQuery".toUri()
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.google.android.youtube")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    // Search results only open; tap the video that matches the name so it really plays.
                    val svc = com.opendroid.ai.accessibility.OpenDroidAccessibilityService.getInstance()
                    var tapped = false
                    if (svc != null) {
                        var tries = 0
                        while (!tapped && tries < 12) {
                            kotlinx.coroutines.delay(1000)
                            tapped = svc.clickFirstVideoResult(query)
                            tries++
                        }
                    }
                    val notes = mutableListOf<String>()
                    val wantsExtras = plan.subscribe || plan.like || plan.comment.isNotBlank()
                    if (wantsExtras) {
                        if (svc == null) {
                            notes.add("Accessibility is off, so I could not subscribe/like/comment")
                        } else if (!tapped) {
                            notes.add("I could not open the video, so no subscribe/like/comment")
                        } else {
                            kotlinx.coroutines.delay(3500) // let the watch page load
                            if (plan.subscribe) {
                                var r = "notfound"
                                var n = 0
                                while (r == "notfound" && n < 6) { r = svc.youtubeSubscribe(); if (r == "notfound") kotlinx.coroutines.delay(1000); n++ }
                                notes.add(when (r) { "clicked" -> "subscribed"; "already" -> "already subscribed"; else -> "subscribe button not found" })
                            }
                            if (plan.like) {
                                var r = "notfound"
                                var n = 0
                                while (r == "notfound" && n < 4) { r = svc.youtubeLike(); if (r == "notfound") kotlinx.coroutines.delay(1000); n++ }
                                notes.add(when (r) { "clicked" -> "liked"; "already" -> "already liked"; else -> "like button not found" })
                            }
                            if (plan.comment.isNotBlank()) {
                                notes.add(if (svc.youtubeComment(plan.comment)) "commented" else "comment could not be posted")
                            }
                        }
                    }
                    val extra = if (notes.isEmpty()) "" else " (" + notes.joinToString(", ") + ")"
                    if (playbackVerifier.awaitVerifiedPlayback(context, "youtube", query)) {
                        ActionResult(true, (if (query.isNotEmpty()) "Playing '$query' on YouTube!" else "YouTube is playing!") + extra, null)
                    } else if (notes.isNotEmpty()) {
                        ActionResult(true, "YouTube opened" + extra, null)
                    } else {
                        ActionResult(false, null, "YouTube opened, but playback could not be verified.")
                    }
                } else {
                    val browserIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(browserIntent)
                    ActionResult(false, null, "The YouTube app isn't installed; I opened the search in your browser, but playback could not be verified.")
                }
            } catch (e: Exception) {
                Log.e("PlayYoutube", "YouTube action failed: ${e.javaClass.simpleName}")
                ActionResult(false, null, "Couldn't open YouTube right now.")
            }
        }
    }

    private class TakePhotoAction : Action {
        override val name: String = "TAKE_PHOTO"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            // Camera hardware is optional (see AndroidManifest uses-feature).
            if (!DeviceCapabilities.hasCamera(context)) {
                return ActionResult(false, null, "This device doesn't have a camera.")
            }
            val camera = params["camera"] ?: "back"
            return try {
                val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (camera.lowercase() == "front") {
                        putExtra("android.intent.extras.CAMERA_FACING", 1)
                        putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                        putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                    } else {
                        putExtra("android.intent.extras.CAMERA_FACING", 0)
                        putExtra("android.intent.extras.LENS_FACING_BACK", 1)
                    }
                }
                context.startActivity(intent)
                ActionResult(true, "Camera is ready — snap away!", null)
            } catch (e: Exception) {
                Log.e("TakePhoto", "Camera failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't open the camera.")
            }
        }
    }

    private class RecordVideoAction : Action {
        override val name: String = "RECORD_VIDEO"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            // Camera hardware is optional (see AndroidManifest uses-feature).
            if (!DeviceCapabilities.hasCamera(context)) {
                return ActionResult(false, null, "This device doesn't have a camera.")
            }
            val camera = params["camera"] ?: "back"
            return try {
                val intent = Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (camera.lowercase() == "front") {
                        putExtra("android.intent.extras.CAMERA_FACING", 1)
                    } else {
                        putExtra("android.intent.extras.CAMERA_FACING", 0)
                    }
                }
                context.startActivity(intent)
                ActionResult(true, "Camera is ready for video!", null)
            } catch (e: Exception) {
                Log.e("RecordVideo", "Video camera failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't open the video camera.")
            }
        }
    }

    companion object {
        private fun sendMediaKeyEvent(context: Context, keyCode: Int) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val eventTime = SystemClock.uptimeMillis()
            
            val downEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0)
            audioManager.dispatchMediaKeyEvent(downEvent)
            
            val upEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0)
            audioManager.dispatchMediaKeyEvent(upEvent)
        }
    }
}
