package com.vougadev.shell.web

import android.Manifest
import android.webkit.PermissionRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A page's call asks the camera and the microphone, and nothing else, or nothing is granted. */
class MediaGrantTest {

    @Test
    fun theCameraAndTheMicrophoneMapToTheirPermissions() {
        val asked = arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE, PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        assertEquals(
            mapOf(asked[0] to Manifest.permission.CAMERA, asked[1] to Manifest.permission.RECORD_AUDIO),
            MediaGrant.permissionsFor(asked),
        )
    }

    @Test
    fun anythingElseIsRefusedWhole() {
        assertNull(MediaGrant.permissionsFor(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE, PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)))
        assertNull(MediaGrant.permissionsFor(emptyArray()))
    }
}
