package com.shilapi.xcertplay.airplay.rcs.transfer

import com.shilapi.xcertplay.airplay.CarPlayThemeAssetStore
import com.shilapi.xcertplay.airplay.rcs.*
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CarPlayThemeTransferHandlerFactory(
    private val directory: File,
    private val store: CarPlayThemeAssetStore,
    private val expectedAssetId: String?,
    private val sdkVersion: String,
    private val onStaged: (com.shilapi.xcertplay.airplay.CarPlayStagedThemeAsset) -> Unit = {},
) : RcsDataStreamHandlerFactory {
    override fun supports(clientType: RcsClientType) = clientType == RcsClientTypes.CAR_PLAY_UPDATE_DATA

    override fun create(stream: RcsDataStream): RcsDataStreamHandler? =
        if (supports(stream.clientType)) Handler(stream) else null

    private inner class Handler(private val stream: RcsDataStream) : RcsDataStreamHandler {
        private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "carplay-theme-timeout").apply { isDaemon = true }
        }
        private val receiver = CarPlayThemeTransferReceiver(directory,
            onComplete = { file, header ->
                val asset = store.stage(file, header, expectedAssetId, sdkVersion)
                onStaged(asset)
                stream.traceProtocol("Theme asset staged assetID=${asset.assetId} version=${asset.version} " +
                    "minimumSDK=${asset.minimumSDKVersion} applied=false")
            }, trace = stream::traceProtocol)

        override fun onStreamOpened(stream: RcsDataStream) {
            stream.traceProtocol("Theme file transfer opened encoding=OPACK storage=disk")
            scheduler.scheduleAtFixedRate({
                runCatching { receiver.expire()?.let { stream.send(RcsMessage.uplink(it)) } }
                    .onFailure { stream.traceProtocol("Theme timeout failed: ${it.message}"); stream.close() }
            }, 1, 1, TimeUnit.SECONDS)
        }

        override fun onMessage(stream: RcsDataStream, message: RcsMessage) {
            require(message.messageType == ApTransportPackageCodec.MESSAGE_TYPE_COMM) { "Invalid theme file carrier" }
            receiver.receive(message.body)?.let { stream.send(RcsMessage.uplink(it)) }
        }

        override fun onStreamClosed(stream: RcsDataStream, cause: Throwable?) {
            scheduler.shutdownNow()
            receiver.close()
            stream.traceProtocol("Theme file transfer closed cause=${cause?.message ?: "EOF"}")
        }
    }
}
