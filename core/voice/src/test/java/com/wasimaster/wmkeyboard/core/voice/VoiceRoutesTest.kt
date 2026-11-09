package com.wasimaster.wmkeyboard.core.voice

import com.wasimaster.wmkeyboard.core.voice.VoiceRoutes.Failure
import com.wasimaster.wmkeyboard.core.voice.VoiceRoutes.Phone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRoutesTest {

    private val google = "com.google.android.googlequicksearchbox/com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
    private val googleTts = "com.google.android.tts/com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService"
    private val bixby = "com.samsung.android.bixby.agent/.mainui.voiceinteraction.RecognitionServiceTrampoline"
    private val futo = "org.futo.voiceinput/.RecognizerService"

    private fun service(component: String) = VoiceRoute.Service(component)

    @Test fun aHealthyPhoneAsksOnDeviceThenTheDefault() {
        val routes = VoiceRoutes.plan(
            Phone(onDevice = true, default = google, defaultPreinstalled = true, others = listOf(google)),
        )
        assertEquals(listOf(VoiceRoute.OnDevice, VoiceRoute.Default), routes)
    }

    /** Xiaomi, Honor: Google's recognizer is installed and nothing is set as the default. */
    @Test fun noDefaultSetStillReachesGoogleByName() {
        val routes = VoiceRoutes.plan(Phone(onDevice = true, default = null, others = listOf(futo, googleTts, google)))
        assertEquals(
            listOf(VoiceRoute.OnDevice, VoiceRoute.Default, service(google), service(googleTts), service(futo)),
            routes,
        )
    }

    /** A maker's assistant as the default may turn the keyboard down; Google's then answers. */
    @Test fun aPreinstalledDefaultIsStoodInFor() {
        val routes = VoiceRoutes.plan(
            Phone(default = bixby, defaultPreinstalled = true, others = listOf(bixby, google)),
        )
        assertEquals(listOf(VoiceRoute.Default, service(google)), routes)
    }

    /** The audio of someone who made an offline recognizer their default goes nowhere else. */
    @Test fun aDefaultTheUserInstalledIsNotStoodInFor() {
        val routes = VoiceRoutes.plan(
            Phone(onDevice = true, default = futo, defaultPreinstalled = false, others = listOf(google, futo)),
        )
        assertEquals(listOf(VoiceRoute.OnDevice, VoiceRoute.Default), routes)
    }

    @Test fun aPickedServiceIsTheOnlyOneAsked() {
        val routes = VoiceRoutes.plan(
            Phone(picked = futo, onDevice = true, default = google, defaultPreinstalled = true, others = listOf(google, futo)),
        )
        assertEquals(listOf(service(futo)), routes)
    }

    /** #1142: the keyboard's own service as the default would hand the phrase back to the keyboard. */
    @Test fun ourOwnDefaultIsNeverAsked() {
        val routes = VoiceRoutes.plan(Phone(default = "ours/.Service", defaultIsOurs = true, others = listOf(google)))
        assertEquals(listOf(service(google)), routes)
        assertTrue(VoiceRoutes.plan(Phone(default = "ours/.Service", defaultIsOurs = true)).isEmpty())
    }

    @Test fun avoidingTheDefaultNamesItWhenItIsAnotherApps() {
        val routes = VoiceRoutes.plan(
            Phone(default = google, defaultPreinstalled = true, others = listOf(futo, google), avoidDefault = true),
        )
        assertEquals(listOf(service(google), service(futo)), routes)
        assertTrue(VoiceRoutes.plan(Phone(avoidDefault = true)).isEmpty())
    }

    @Test fun aPhoneWithNoRecognizerHasNothingToAsk() {
        assertTrue(VoiceRoutes.plan(Phone()).isEmpty())
        assertEquals(listOf(VoiceRoute.OnDevice), VoiceRoutes.plan(Phone(onDevice = true)))
    }

    @Test fun onlyAFailureToStartMovesOn() {
        // Not installed, no default set, disconnected, busy, refused, language unknown.
        listOf(
            VoiceRoutes.ERROR_CLIENT, VoiceRoutes.ERROR_TOO_MANY_REQUESTS, VoiceRoutes.ERROR_SERVER_DISCONNECTED,
            VoiceRoutes.ERROR_RECOGNIZER_BUSY, VoiceRoutes.ERROR_INSUFFICIENT_PERMISSIONS, VoiceRoutes.ERROR_SERVER,
            VoiceRoutes.ERROR_LANGUAGE_NOT_SUPPORTED, VoiceRoutes.ERROR_LANGUAGE_UNAVAILABLE, 14, 99,
        ).forEach { assertTrue("error $it", VoiceRoutes.cannotStart(it)) }
        // Offline, the microphone held elsewhere, and silence follow to any recognizer.
        listOf(
            VoiceRoutes.ERROR_NETWORK, VoiceRoutes.ERROR_NETWORK_TIMEOUT, VoiceRoutes.ERROR_AUDIO,
            VoiceRoutes.ERROR_NO_MATCH, VoiceRoutes.ERROR_SPEECH_TIMEOUT,
        ).forEach { assertFalse("error $it", VoiceRoutes.cannotStart(it)) }
    }

    @Test fun theOnDeviceFailureIsToldOnlyWhenItIsTheOnlyOne() {
        val onDevice = Failure(VoiceRoute.OnDevice, VoiceRoutes.ERROR_LANGUAGE_UNAVAILABLE)
        val default = Failure(VoiceRoute.Default, VoiceRoutes.ERROR_CLIENT)
        assertEquals(default, VoiceRoutes.toTell(listOf(onDevice, default)))
        assertEquals(onDevice, VoiceRoutes.toTell(listOf(onDevice)))
    }

    @Test fun theMostUsefulFailureIsTold() {
        val unset = Failure(VoiceRoute.Default, VoiceRoutes.ERROR_CLIENT)
        val language = Failure(service(google), VoiceRoutes.ERROR_LANGUAGE_NOT_SUPPORTED)
        val refused = Failure(service(bixby), VoiceRoutes.ERROR_INSUFFICIENT_PERMISSIONS)
        assertEquals(language, VoiceRoutes.toTell(listOf(unset, language)))
        assertEquals(refused, VoiceRoutes.toTell(listOf(unset, language, refused)))
        // Equally useful: the one asked first.
        val gone = Failure(service(futo), VoiceRoutes.ERROR_TOO_MANY_REQUESTS)
        assertEquals(unset, VoiceRoutes.toTell(listOf(unset, gone)))
    }
}
