package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.net.Socket
import java.net.InetAddress
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayMediaEngineTest {
    @Test
    fun streamConnectionIdUsesUnsignedDecimalForHkdfSalt() {
        assertEquals("18446744073709551615", unsignedPlistDecimal(-1L))
        assertEquals(
            BigInteger("18446744073709551615"),
            unsignedPlistInteger(-1L),
        )
    }

    @Test
    fun keyFrameCommandsAddressScreensByDisplayUuid() {
        assertEquals(mapOf("type" to "forceKeyFrame", "params" to emptyMap<String, Any?>()), mainScreenKeyFrameCommand())
        assertEquals(
            mapOf("type" to "forceKeyFrame", "params" to mapOf("uuid" to AirPlayInfoPlist.ALT_UUID)),
            screenKeyFrameCommand(AirPlayInfoPlist.ALT_UUID),
        )
    }

    @Test
    fun screenStreamTeardownReportsInactive() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val clearedRecovery = mutableListOf<Int>()
        val sink = object : MediaSink {
            override fun onVideoRecoveryHandler(type: Int, requestKeyFrame: (() -> Boolean)?) {
                if (requestKeyFrame == null) clearedRecovery += type
            }
            override fun onScreenStreamActive(type: Int, active: Boolean) {
                events += type to active
            }
        }
        val session = testSession()

        try {
            val engine = CarPlayMediaEngine(sink)
            engine.onTeardown(session, 110)
            engine.onTeardown(session, 100)
        } finally {
            session.close()
        }

        assertEquals(listOf(110 to false), events)
        assertEquals(listOf(110), clearedRecovery)
    }

    @Test
    fun sessionCloseReportsAllScreenStreamsInactive() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val sink = object : MediaSink {
            override fun onScreenStreamActive(type: Int, active: Boolean) {
                events += type to active
            }
        }
        val session = testSession()
        val engine = CarPlayMediaEngine(sink)
        val streamsField = CarPlayMediaEngine::class.java.getDeclaredField("streams").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val streams = streamsField.get(engine) as
            MutableMap<CarPlayMediaEngine.StreamKey, Closeable>
        streams[CarPlayMediaEngine.StreamKey(session, 110)] = Closeable {}
        streams[CarPlayMediaEngine.StreamKey(session, 111)] = Closeable {}
        streams[CarPlayMediaEngine.StreamKey(session, 100)] = Closeable {}

        engine.onSessionClosed(session)
        session.close()

        assertEquals(setOf(110 to false, 111 to false), events.toSet())
        assertTrue(streams.isEmpty())
    }

    @Test
    fun teardownStopsEveryAudioTypeSharingTheStreamType() {
        val stopped = mutableListOf<AudioStreamId>()
        val sink = object : MediaSink {
            override fun onAudioStopped(id: AudioStreamId) {
                stopped += id
            }
        }
        val session = testSession()
        val engine = CarPlayMediaEngine(sink)
        val streamsField = CarPlayMediaEngine::class.java.getDeclaredField("streams").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val streams = streamsField.get(engine) as
            MutableMap<CarPlayMediaEngine.StreamKey, Closeable>
        streams[CarPlayMediaEngine.StreamKey(session, 100, "media")] = Closeable {}
        streams[CarPlayMediaEngine.StreamKey(session, 100, "speechrecognition")] = Closeable {}
        streams[CarPlayMediaEngine.StreamKey(session, 101, "media")] = Closeable {}

        engine.onTeardown(session, 100)
        session.close()

        assertEquals(
            setOf(AudioStreamId(100, "media"), AudioStreamId(100, "speechrecognition")),
            stopped.toSet(),
        )
        assertEquals(setOf(CarPlayMediaEngine.StreamKey(session, 101, "media")), streams.keys)
    }

    @Test
    fun microphoneFollowsThePhonesInputPortForAnyMainAudioCategory() {
        val requested = mapOf("audioType" to "default", "dataPort" to 12345)
        assertEquals(12345, requestedMicrophonePort(true, 100, requested))
        assertEquals(
            12345,
            requestedMicrophonePort(true, 100, mapOf("audioType" to "compatibility", "dataPort" to 12345)),
        )
        assertEquals(null, requestedMicrophonePort(false, 100, requested))
        assertEquals(null, requestedMicrophonePort(true, 101, requested))
        assertEquals(null, requestedMicrophonePort(true, 100, mapOf("dataPort" to 0)))
        assertEquals(null, requestedMicrophonePort(true, 100, mapOf("dataPort" to 65536)))
        assertEquals(null, requestedMicrophonePort(true, 100, emptyMap()))
    }

    @Test
    fun opusMicrophoneRateFollowsTheSelectedFormatBit() {
        assertEquals(16_000, AudioStreamCodec.opusCaptureRate(0x10000000L))
        assertEquals(24_000, AudioStreamCodec.opusCaptureRate(0x20000000L))
        assertEquals(48_000, AudioStreamCodec.opusCaptureRate(0x40000000L))
    }

    @Test
    fun microphoneStartsAfterSetupResponseWithoutWaitingForDownlinkAudio() {
        val events = mutableListOf<String>()
        val sink = object : MediaSink {
            override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {
                events += "start:${id.type}:${config.audioType}"
            }

            override fun onMicrophoneStopped(id: AudioStreamId) {
                events += "stop:${id.type}"
            }
        }
        val session = testSession()
        val engine = CarPlayMediaEngine(sink, microphoneEnabled = true)
        val pendingField = CarPlayMediaEngine::class.java.getDeclaredField("pendingMicrophone").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val pending = pendingField.get(engine) as MutableMap<CarPlayMediaEngine.StreamKey, MicrophoneConfig>
        pending[CarPlayMediaEngine.StreamKey(session, 100)] = MicrophoneConfig(
            audioType = "default",
            sampleRate = 16_000,
            channels = 1,
            payloadType = 100,
            frameMillis = 20,
            host = InetAddress.getLoopbackAddress(),
            port = 12345,
            key = ByteArray(32),
        )

        engine.onSetupResponseSent(session)
        engine.onSetupResponseSent(session)
        assertEquals(listOf("start:100:default"), events)

        engine.onTeardown(session, 100)
        session.close()
        assertEquals(listOf("start:100:default", "stop:100"), events)
    }

    @Test
    fun sessionCloseStopsAnActiveMicrophone() {
        val events = mutableListOf<String>()
        val sink = object : MediaSink {
            override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {
                events += "start"
            }

            override fun onMicrophoneStopped(id: AudioStreamId) {
                events += "stop"
            }
        }
        val session = testSession()
        val engine = CarPlayMediaEngine(sink, microphoneEnabled = true)
        val pendingField = CarPlayMediaEngine::class.java.getDeclaredField("pendingMicrophone").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val pending = pendingField.get(engine) as MutableMap<CarPlayMediaEngine.StreamKey, MicrophoneConfig>
        pending[CarPlayMediaEngine.StreamKey(session, 100)] = MicrophoneConfig(
            audioType = "default",
            sampleRate = 16_000,
            channels = 1,
            payloadType = 100,
            frameMillis = 20,
            host = InetAddress.getLoopbackAddress(),
            port = 12345,
            key = ByteArray(32),
        )

        engine.onSetupResponseSent(session)
        engine.onSessionClosed(session)
        session.close()

        assertEquals(listOf("start", "stop"), events)
        assertTrue(pending.isEmpty())
    }

    private fun testSession(): AirPlaySession = AirPlaySession(
        socket = Socket(),
        config = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0",
            main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
        ),
        identity = AirPlayIdentity.generate(),
        pairings = PairingStore(),
        mfi = null,
        listener = object : AirPlaySessionListener {},
        media = object : AirPlayMediaHandler {},
    )
}
