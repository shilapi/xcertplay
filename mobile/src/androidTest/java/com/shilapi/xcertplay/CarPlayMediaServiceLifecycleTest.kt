package com.shilapi.xcertplay

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs without additional test dependencies: connectedDebugAndroidTest on an Android device. */
class CarPlayMediaServiceLifecycleTest : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        try {
            runTest("backgroundReconnectKeepsOwnerBinding", 1, ::backgroundReconnectKeepsOwnerBinding)
            runTest("realServiceCanBindAndRelease", 2, ::realServiceCanBindAndRelease)
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "OK (2 lifecycle tests)\n") })
        } catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply {
                putString("stream", "FAIL: ${error.stackTraceToString()}\n")
            })
        }
    }

    private fun runTest(name: String, index: Int, body: () -> Unit) {
        val status = Bundle().apply {
            putString("id", "InstrumentationTestRunner")
            putString("class", this@CarPlayMediaServiceLifecycleTest.javaClass.name)
            putString("test", name)
            putInt("numtests", 2)
            putInt("current", index)
        }
        sendStatus(1, status)
        try {
            body()
            sendStatus(0, status)
        } catch (error: Throwable) {
            status.putString("stack", error.stackTraceToString())
            sendStatus(-2, status)
            throw error
        }
    }

    private fun backgroundReconnectKeepsOwnerBinding() {
        val context = RecordingContext(targetContext)
        val first = Any()
        val replacement = Any()
        try {
            attach(context, first)
            check(context.binds == 1) { "Background attach must bind the media service" }
            attach(context, replacement)
            check(context.binds == 1) { "Replacing the owner must reuse the service binding" }
            detach(context, first)
            check(context.unbinds == 0) { "An obsolete owner must not release the current service" }
            detach(context, replacement)
            check(context.unbinds == 1) { "The current owner must release its binding" }
            attach(context, first)
            check(context.binds == 2) { "A background reconnect must bind again" }
        } finally {
            detach(context, first)
            detach(context, replacement)
        }
    }

    private fun realServiceCanBindAndRelease() {
        val context = RecordingContext(targetContext, realBinding = true)
        val owner = Any()
        try {
            attach(context, owner)
            check(context.connected.await(10, TimeUnit.SECONDS)) { "Media3 service did not connect" }
            check(context.binds == 1)
        } finally {
            detach(context, owner)
        }
        check(context.unbinds == 1)
        waitForIdleSync()
    }

    // The bridge is internal to :shared; reflection exercises its real Android entry point.
    private val bridgeClass get() = Class.forName("com.shilapi.xcertplay.media.CarPlayMediaSessionBridge")
    private val bridge get() = bridgeClass.getField("INSTANCE").get(null)

    private fun attach(context: Context, owner: Any) {
        try {
            bridgeClass.getMethod("attach", Context::class.java, Any::class.java, Function1::class.java)
                .invoke(bridge, context, owner, { _: Any -> true })
        } catch (error: java.lang.reflect.InvocationTargetException) {
            throw error.targetException
        }
    }

    private fun detach(context: Context, owner: Any) {
        bridgeClass.getMethod("detach", Context::class.java, Any::class.java).invoke(bridge, context, owner)
    }

    private class RecordingContext(base: Context, val realBinding: Boolean = false) : ContextWrapper(base) {
        var binds = 0
        var unbinds = 0
        val connected = CountDownLatch(1)
        private val connections = mutableMapOf<ServiceConnection, ServiceConnection>()

        override fun getApplicationContext(): Context = this

        override fun startService(service: Intent): ComponentName? =
            throw IllegalStateException("Not allowed to start service: app is in background")

        override fun stopService(service: Intent): Boolean = true

        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
            check(service.action == "androidx.media3.session.MediaSessionService")
            check(flags and Context.BIND_AUTO_CREATE != 0)
            binds++
            if (!realBinding) return true
            val delegate = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: android.os.IBinder) {
                    connection.onServiceConnected(name, binder)
                    connected.countDown()
                }
                override fun onServiceDisconnected(name: ComponentName) = connection.onServiceDisconnected(name)
            }
            connections[connection] = delegate
            return super.bindService(service, delegate, flags)
        }

        override fun unbindService(connection: ServiceConnection) {
            unbinds++
            if (realBinding) super.unbindService(checkNotNull(connections.remove(connection)))
        }
    }
}
