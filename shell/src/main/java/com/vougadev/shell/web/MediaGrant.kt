package com.vougadev.shell.web

import android.Manifest
import android.webkit.PermissionRequest

/**
 * The camera and the microphone a page of the app's own origin asks for (a video call over WebRTC): the Android
 * permission behind each resource the WebView names. Pure.
 */
object MediaGrant {

    /** {resource: permission}, or null when the page asks anything else too (protected media, MIDI): refused whole. */
    fun permissionsFor(resources: Array<String>): Map<String, String>? {
        if (resources.isEmpty()) return null
        return resources.associateWith {
            when (it) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
                else -> return null
            }
        }
    }
}
