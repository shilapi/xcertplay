package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.*
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCarrier
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncMessageType
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CarPlayDataStreamLifecycleTest {
    @Test fun unansweredUiSyncResetIsRetriedByTheWatchdogWithoutClosingAnyStream() {
        Fixture(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL, CarPlayUiSyncHandlerFactory("vehicle")).use { f ->
            f.phone().use { phone ->
                fun nextReset() = CarPlayUiSyncCarrier.decode(phone.receiveNext(5_000)!!.body).message
                    .also { assertEquals(CarPlayUiSyncMessageType.RESET, it.type) }
                assertEquals("Accessory sends its initial RESET", 1L, nextReset().sessionSequenceNumber)
                assertNull("Watchdog waits 2 s", phone.receiveNext(1_500))
                assertEquals("2 s watchdog restarts the handshake", 2L, nextReset().sessionSequenceNumber)
                assertFalse("Instrument handshake must keep its stream open",
                    f.streamEnded.await(200, TimeUnit.MILLISECONDS))
                assertFalse("Instrument handshake failure must not disconnect the main screen",
                    f.ended.await(200, TimeUnit.MILLISECONDS))
                assertFalse(f.control.isClosed)
            }
        }
    }

    @Test fun peerDataStreamEofDoesNotEndOtherwiseActiveAirPlay() {
        val factory = RcsDataStreamHandlerFactory { object : RcsDataStreamHandler {} }
        listOf(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL, RcsClientTypes.CAR_PLAY_UPDATE_DATA).forEach { type ->
            Fixture(type, factory).use { f ->
                f.phone().use { assertTrue(f.stream.awaitPeerConnection(5_000)) }
                assertTrue(f.streamEnded.await(5, TimeUnit.SECONDS))
                assertFalse(f.ended.await(200, TimeUnit.MILLISECONDS))
                assertFalse(f.control.isClosed)
            }
        }
    }

    @Test fun localClusterTeardownDoesNotEndOtherwiseActiveSession() {
        Fixture(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL,
            RcsDataStreamHandlerFactory { object : RcsDataStreamHandler {} }).use { f ->
            f.phone().use {
                assertTrue(f.stream.awaitPeerConnection(5_000))
                f.stream.close()
                assertTrue(f.streamEnded.await(5, TimeUnit.SECONDS))
                assertFalse(f.ended.await(100, TimeUnit.MILLISECONDS))
                assertFalse(f.control.isClosed)
            }
        }
    }

    private class Fixture(type: RcsClientType, factory: RcsDataStreamHandlerFactory) : AutoCloseable {
        val ended = CountDownLatch(1)
        val streamEnded = CountDownLatch(1)
        val control = Socket()
        private val session = AirPlaySession(control, AirPlayConfig("test", "test", "", "test",
            AirPlayDisplayConfig(1280, 720)), AirPlayIdentity.generate(), PairingStore(), null,
            object : AirPlaySessionListener {
                override fun onSessionEnded(session: AirPlaySession) { ended.countDown() }
            }, object : AirPlayMediaHandler {})
        private val opened: OpenedCarPlayDataStream
        val stream: RcsDataStream
        private val readKey: ByteArray
        private val writeKey: ByteArray
        init {
            // Obtain a real pair-verify shared secret without starting the Android control server.
            session.pairVerify.handle(Tlv8Codec.encode(listOf(Tlv8Item(6, byteArrayOf(1)),
                Tlv8Item(3, AirPlayCrypto.x25519Generate().publicKey))))
            val shared = requireNotNull(session.sharedSecret)
            fun key(label: String) = AirPlayCrypto.hkdfSha512(shared,
                "DataStream-Salt7".toByteArray(), label.toByteArray())
            readKey = key("DataStream-Input-Encryption-Key")
            writeKey = key("DataStream-Output-Encryption-Key")
            val observedFactory = object : RcsDataStreamHandlerFactory {
                override fun supports(clientType: RcsClientType) = factory.supports(clientType)
                override fun create(stream: RcsDataStream): RcsDataStreamHandler? {
                    val target = factory.create(stream) ?: return null
                    return object : RcsDataStreamHandler by target {
                        override fun onStreamClosed(stream: RcsDataStream, cause: Throwable?) {
                            try { target.onStreamClosed(stream, cause) } finally { streamEnded.countDown() }
                        }
                    }
                }
            }
            opened = requireNotNull(CarPlayDataStreamFactory().open(session, type, "7",
                CarPlayDataStreamHandlers(null, observedFactory, {})))
            stream = opened.closeable as RcsDataStream
            assertTrue(opened.attach())
        }
        fun phone(): RcsChannel = RcsChannel.connect(InetSocketAddress("127.0.0.1", opened.port), readKey, writeKey)
        override fun close() { opened.closeable.close(); session.close() }
    }
}
