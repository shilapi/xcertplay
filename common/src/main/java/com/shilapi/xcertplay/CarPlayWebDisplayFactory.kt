package com.shilapi.xcertplay

import android.content.Context
import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.media.AndroidMediaSink
import fi.iki.elonen.NanoHTTPD
import java.io.Closeable
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** An enabled web display owns its negotiated size; window changes only resize its preview. */
data class CarPlayWebDisplayConfig(
    val enabled: Boolean = false,
    val width: Int = 1280,
    val height: Int = 720,
    val preview: Boolean = true,
) {
    init {
        require(width in 320..3840 && height in 240..2160 && width % 2 == 0 && height % 2 == 0) {
            "Resolution must use even dimensions between 320 x 240 and 3840 x 2160"
        }
    }

    fun override(display: AirPlayDisplayConfig): AirPlayDisplayConfig = if (!enabled) display else display.copy(
        widthPixels = width,
        heightPixels = height,
        heightPhysicalMm = display.widthPhysicalMm?.let { maxOf(1, Math.round(it * height.toDouble() / width).toInt()) },
        viewArea = null,
        safeArea = null,
    )
}

/** Creates both displays as one session, with one HTTP endpoint and separate decoder surfaces. */
object CarPlayWebDisplayFactory {
    const val MAIN = 110
    const val CLUSTER = 111
    const val PORT = 8080
    private val sessions = ConcurrentHashMap<AndroidMediaSink, Session>()

    fun find(sink: AndroidMediaSink?): Session? = sink?.let(sessions::get)

    fun addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }.mapNotNull { it.hostAddress }.distinct().sorted()
    }.getOrDefault(emptyList())

    fun urls(type: Int, key: String): List<String> = addresses().map {
        "http://$it:$PORT/${if (type == MAIN) "main" else "cluster"}?key=$key"
    }

    fun create(
        context: Context,
        configs: Map<Int, CarPlayWebDisplayConfig>,
        key: String,
        sendTouch: (List<AirPlayContact>) -> Boolean,
    ): Session? {
        val enabled = configs.filterValues { it.enabled }
        if (enabled.isEmpty()) return null
        val channels = enabled.mapValues { (_, config) -> WebDisplayChannel(config.width, config.height) }
        val server = WebDisplayServer(context.assets.open("carplay-display.html").use { it.readBytes() }, channels, key, sendTouch)
        val outputs = linkedMapOf<Int, CarPlayWebVideoOutput>()
        try {
            server.start(3000, true)
            enabled.forEach { (type, config) ->
                val channel = channels.getValue(type)
                outputs[type] = CarPlayWebVideoOutput(config.width, config.height,
                    wanted = { channel.hasViewer }, publish = channel::publish)
            }
            return Session(configs, channels, outputs, server)
        } catch (error: Exception) {
            outputs.values.forEach { it.close() }
            server.stop()
            throw error
        }
    }

    class Session internal constructor(
        private val configs: Map<Int, CarPlayWebDisplayConfig>,
        private val channels: Map<Int, WebDisplayChannel>,
        private val outputs: Map<Int, CarPlayWebVideoOutput>,
        private val server: WebDisplayServer,
    ) : Closeable {
        private val closed = AtomicBoolean(false)
        private var sink: AndroidMediaSink? = null
        fun bind(sink: AndroidMediaSink) {
            this.sink = sink
            sessions[sink] = this
            outputs.forEach { (type, output) -> sink.setSurface(type, output.surface) }
        }
        fun owns(type: Int) = outputs.containsKey(type)
        fun preview(type: Int, surface: Surface?) {
            outputs[type]?.setPreview(surface.takeIf { configs[type]?.preview == true })
        }
        fun streamActive(type: Int, active: Boolean) {
            channels[type]?.setActive(active)
            outputs[type]?.setActive(active)
        }
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            sink?.let { sessions.remove(it, this) }; sink = null
            server.stop()
            outputs.values.forEach { it.close() }
        }
    }
}

/** Only the newest complete image is retained; slow browsers never build a frame backlog. */
internal class WebDisplayChannel(val width: Int, val height: Int, private val now: () -> Long = System::nanoTime) {
    data class Frame(val bytes: ByteArray, val sequence: Long)
    @Volatile var frame: Frame? = null; private set
    @Volatile var active = false; private set
    @Volatile private var lastViewer: Long? = null
    private var sequence = 0L
    val hasViewer get() = lastViewer?.let { now() - it < TimeUnit.SECONDS.toNanos(3) } == true
    fun viewed() { lastViewer = now() }
    @Synchronized fun setActive(value: Boolean) { active = value; if (!value) frame = null }
    @Synchronized fun publish(bytes: ByteArray) { if (active) frame = Frame(bytes, ++sequence) }
}

/** A short lease prevents lost up events or a vanished browser from leaving fingers pressed. */
internal class WebDisplayTouch(private val send: (List<AirPlayContact>) -> Boolean) {
    private var owner: String? = null
    private var touchedAt = 0L
    @Synchronized fun submit(client: String, value: String, now: Long): Boolean {
        require(client.matches(Regex("[a-zA-Z0-9-]{1,64}")))
        val contacts = if (value.isEmpty()) emptyList() else value.split(';').map {
            val fields = it.split(',')
            require(fields.size == 3)
            AirPlayContact(fields[0].toInt(), fields[1].toDouble(), fields[2].toDouble(), true)
        }
        require(contacts.size <= 2 && contacts.map { it.id }.distinct().size == contacts.size && contacts.all {
            it.id in 0..1 && it.x.isFinite() && it.y.isFinite() && it.x in 0.0..1.0 && it.y in 0.0..1.0
        })
        expire(now)
        if (owner != null && owner != client) return false
        val slots = if (contacts.isEmpty()) emptyList() else (0..1).map { id ->
            contacts.find { it.id == id } ?: AirPlayContact(id, 0.0, 0.0, false)
        }
        if (!send(slots)) return false
        touchedAt = now
        owner = client.takeIf { contacts.isNotEmpty() }
        return true
    }
    @Synchronized fun expire(now: Long) { if (owner != null && now - touchedAt >= 1500) release() }
    @Synchronized fun release() { if (owner != null) send(emptyList()); owner = null }
}

internal class WebDisplayServer(
    private val page: ByteArray,
    private val channels: Map<Int, WebDisplayChannel>,
    private val key: String,
    sendTouch: (List<AirPlayContact>) -> Boolean,
    port: Int = CarPlayWebDisplayFactory.PORT,
) : NanoHTTPD("0.0.0.0", port) {
    private val touch = WebDisplayTouch(sendTouch)
    private val watchdog = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "carplay-web-touch").apply { isDaemon = true }
    }
    init { watchdog.scheduleWithFixedDelay({ touch.expire(System.nanoTime() / 1_000_000) }, 250, 250, TimeUnit.MILLISECONDS) }

    override fun serve(request: IHTTPSession): Response {
        if (request.parameters["key"]?.firstOrNull() != key) return text(Response.Status.UNAUTHORIZED, "Display key required")
        val parts = request.uri.trim('/').split('/')
        val type = when (parts.firstOrNull()) { "main" -> 110; "cluster" -> 111; else -> return text(Response.Status.NOT_FOUND, "Display not found") }
        val channel = channels[type] ?: return text(Response.Status.NOT_FOUND, "Display is disabled")
        if (parts.size == 1 && request.method == Method.GET) return bytes(Response.Status.OK, "text/html; charset=utf-8", page)
        if (parts.size != 2) return text(Response.Status.NOT_FOUND, "Not found")
        return when (parts[1]) {
            "frame" -> {
                if (request.method != Method.GET) return text(Response.Status.METHOD_NOT_ALLOWED, "GET only")
                channel.viewed()
                val frame = channel.frame
                val after = request.parameters["after"]?.firstOrNull()?.toLongOrNull()
                val response = if (frame == null || frame.sequence == after) text(Response.Status.NO_CONTENT, "")
                    else bytes(Response.Status.OK, "image/jpeg", frame.bytes)
                response.addHeader("X-Frame-Sequence", (frame?.sequence ?: 0).toString())
                response.addHeader("X-Stream-Active", channel.active.toString())
                response.addHeader("X-Display-Width", channel.width.toString())
                response.addHeader("X-Display-Height", channel.height.toString())
                response
            }
            "touch" -> {
                if (request.method != Method.POST || type != 110) return text(Response.Status.METHOD_NOT_ALLOWED, "Main display POST only")
                val sameOrigin = runCatching {
                    val origin = URI(request.headers["origin"] ?: "")
                    origin.scheme == "http" && origin.rawAuthority.equals(request.headers["host"], true)
                }.getOrDefault(false)
                if (!sameOrigin) return text(Response.Status.FORBIDDEN, "Origin rejected")
                if (!channel.active) return text(Response.Status.CONFLICT, "Display is inactive")
                try {
                    val accepted = touch.submit(request.parameters["client"]?.firstOrNull().orEmpty(),
                        request.parameters["contacts"]?.firstOrNull().orEmpty(), System.nanoTime() / 1_000_000)
                    text(if (accepted) Response.Status.OK else Response.Status.CONFLICT, if (accepted) "OK" else "Display is busy")
                } catch (_: IllegalArgumentException) { text(Response.Status.BAD_REQUEST, "Invalid touch") }
            }
            else -> text(Response.Status.NOT_FOUND, "Not found")
        }
    }
    private fun text(status: Response.Status, value: String) = newFixedLengthResponse(status, "text/plain", value).apply { headers() }
    private fun bytes(status: Response.Status, mime: String, value: ByteArray) =
        newFixedLengthResponse(status, mime, value.inputStream(), value.size.toLong()).apply { headers() }
    private fun Response.headers() {
        addHeader("Cache-Control", "no-store")
        addHeader("Referrer-Policy", "no-referrer")
        addHeader("X-Content-Type-Options", "nosniff")
    }
    override fun stop() { watchdog.shutdownNow(); touch.release(); super.stop() }
}
