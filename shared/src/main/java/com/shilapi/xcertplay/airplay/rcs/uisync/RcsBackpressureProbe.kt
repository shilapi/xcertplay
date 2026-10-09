package com.shilapi.xcertplay.airplay.rcs.uisync

import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Opt-in transport experiment: retransmit one valid framed message, without allocating new UI sequences. */
internal object RcsBackpressureProbe {
    fun run(message: RcsMessage, targetBytes: Int, send: (RcsMessage) -> Unit,
        onTimeout: (Throwable) -> Unit, trace: (String) -> Unit, timeoutMillis: Long = 5_000) {
        require(targetBytes in 1_048_577..2_097_152 && timeoutMillis > 0)
        val done = AtomicBoolean(false)
        val timedOut = AtomicBoolean(false)
        val written = AtomicLong()
        val start = System.nanoTime()
        val timer = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "carplay-rcs-probe-timeout").apply { isDaemon = true }
        }
        val timeout = timer.schedule({
            if (done.compareAndSet(false, true)) {
                timedOut.set(true)
                trace("RCS backpressure probe timeout writtenPlainBytes=${written.get()} timeoutMillis=$timeoutMillis")
                onTimeout(IOException("RCS backpressure probe write timeout"))
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS)
        trace("RCS backpressure probe start targetPlainBytes=$targetBytes duplicatePsn=true")
        try {
            val size = message.encoded().size.toLong()
            var frames = 0
            var maxWriteMillis = 0L
            var nextProgress = 262_144L
            while (written.get() < targetBytes && !timedOut.get()) {
                val before = System.nanoTime()
                send(message)
                maxWriteMillis = maxOf(maxWriteMillis, (System.nanoTime() - before) / 1_000_000)
                written.addAndGet(size); frames++
                if (written.get() >= nextProgress) {
                    trace("RCS backpressure probe progress writtenPlainBytes=${written.get()} frames=$frames maxWriteMillis=$maxWriteMillis")
                    nextProgress += 262_144
                }
            }
            if (!done.compareAndSet(false, true)) throw IOException("RCS backpressure probe timed out")
            trace("RCS backpressure probe complete writtenPlainBytes=${written.get()} frames=$frames " +
                "elapsedMillis=${(System.nanoTime() - start) / 1_000_000} maxWriteMillis=$maxWriteMillis; socket writes are not business acknowledgements")
        } finally {
            done.set(true); timeout.cancel(false); timer.shutdownNow()
        }
    }
}
