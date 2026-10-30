package com.wasimaster.wmkeyboard.ime.send

import android.content.Context
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.send.PairDropRtc
import com.wasimaster.wmkeyboard.core.send.pairDropIce
import com.wasimaster.wmkeyboard.core.send.pairDropSdp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer

/**
 * WebRTC data channels for [com.wasimaster.wmkeyboard.core.send.PairDropClient], over
 * the `webrtc` gate's library. Not in F-Droid's build, which may hold no prebuilt
 * library of this size: see the stand-in in src/nogoogle.
 */
internal object PairDropWebRtc {

    /** This build can have the library at all. */
    const val supported = true

    private const val NATIVE_LIBRARY = "jingle_peerconnection_so"

    /** Null when the library would not load; the caller goes on without peer-to-peer. */
    fun create(context: Context): PairDropRtc? = runCatching {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setNativeLibraryName(NATIVE_LIBRARY)
                .setNativeLibraryLoader { name ->
                    runCatching { FeatureModules.loadLibrary(name) }.isSuccess
                }
                .createInitializationOptions(),
        )
        Bridge(PeerConnectionFactory.builder().createPeerConnectionFactory())
    }.getOrNull()

    private class Bridge(private val factory: PeerConnectionFactory) : PairDropRtc {

        override fun connect(peerId: String, caller: Boolean, iceServers: JsonElement?, events: PairDropRtc.Events): PairDropRtc.Connection {
            val config = PeerConnection.RTCConfiguration(parseIceServers(iceServers)).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }
            val link = Link(caller, events)
            link.peer = requireNotNull(factory.createPeerConnection(config, link)) { "No peer connection" }
            if (caller) link.offer()
            return link
        }
    }

    private fun parseIceServers(element: JsonElement?): List<PeerConnection.IceServer> =
        (element as? JsonArray).orEmpty().mapNotNull { entry ->
            val server = entry as? JsonObject ?: return@mapNotNull null
            val urls = when (val value = server["urls"]) {
                is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                is JsonPrimitive -> listOfNotNull(value.contentOrNull)
                else -> emptyList()
            }
            if (urls.isEmpty()) return@mapNotNull null
            val builder = PeerConnection.IceServer.builder(urls)
            (server["username"] as? JsonPrimitive)?.contentOrNull?.let(builder::setUsername)
            (server["credential"] as? JsonPrimitive)?.contentOrNull?.let(builder::setPassword)
            builder.createIceServer()
        }

    private class Link(private val caller: Boolean, private val events: PairDropRtc.Events) :
        PeerConnection.Observer, PairDropRtc.Connection {

        lateinit var peer: PeerConnection

        @Volatile private var channel: DataChannel? = null

        fun offer() {
            attach(peer.createDataChannel(CHANNEL_LABEL, DataChannel.Init().apply { ordered = true }))
            peer.createOffer(described { local -> sendDescription(local) }, MediaConstraints())
        }

        private fun sendDescription(local: SessionDescription) {
            peer.setLocalDescription(quiet { }, local)
            events.onSignal(
                buildJsonObject {
                    putJsonObject("sdp") {
                        put("type", local.type.canonicalForm())
                        put("sdp", local.description)
                    }
                },
            )
        }

        override fun onSignal(signal: JsonObject) {
            signal.pairDropSdp()?.let { (type, text) ->
                val description = SessionDescription(SessionDescription.Type.fromCanonicalForm(type), text)
                peer.setRemoteDescription(
                    quiet {
                        if (description.type == SessionDescription.Type.OFFER) {
                            peer.createAnswer(described { local -> sendDescription(local) }, MediaConstraints())
                        }
                    },
                    description,
                )
                return
            }
            signal.pairDropIce()?.let { (mid, index, candidate) ->
                peer.addIceCandidate(IceCandidate(mid.orEmpty(), index, candidate))
            }
        }

        override fun sendText(text: String) {
            channel?.send(DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray()), false))
        }

        override fun sendBytes(bytes: ByteArray, length: Int) {
            // A copy: the caller refills its array for the next chunk while this one is still queued.
            channel?.send(DataChannel.Buffer(ByteBuffer.wrap(bytes.copyOf(length)), true))
        }

        override fun close() {
            runCatching { channel?.close() }
            runCatching { peer.close() }
        }

        private fun attach(opened: DataChannel) {
            channel = opened
            opened.registerObserver(
                object : DataChannel.Observer {
                    override fun onBufferedAmountChange(previousAmount: Long) = Unit

                    override fun onStateChange() {
                        when (opened.state()) {
                            DataChannel.State.OPEN -> events.onOpen()
                            DataChannel.State.CLOSED -> events.onClosed()
                            else -> Unit
                        }
                    }

                    override fun onMessage(buffer: DataChannel.Buffer) {
                        val bytes = ByteArray(buffer.data.remaining())
                        buffer.data.get(bytes)
                        if (buffer.binary) events.onBytes(bytes) else events.onText(String(bytes))
                    }
                },
            )
        }

        override fun onDataChannel(opened: DataChannel) {
            if (!caller) attach(opened)
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            events.onSignal(
                buildJsonObject {
                    putJsonObject("ice") {
                        put("candidate", candidate.sdp)
                        put("sdpMid", candidate.sdpMid)
                        put("sdpMLineIndex", candidate.sdpMLineIndex)
                    }
                },
            )
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            if (state == PeerConnection.IceConnectionState.FAILED || state == PeerConnection.IceConnectionState.CLOSED) events.onClosed()
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
    }

    private const val CHANNEL_LABEL = "data-channel"

    /** An observer for createOffer and createAnswer: only the description it makes matters. */
    private fun described(onMade: (SessionDescription) -> Unit) = object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = onMade(description)
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }

    /** An observer for setting a description: [onSet] once it took. */
    private fun quiet(onSet: () -> Unit) = object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = onSet()
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }
}
