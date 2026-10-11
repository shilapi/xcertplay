package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.VideoCodec

/** Selects the encoded source of each web display. */
internal class WebDisplayVideoOutput(
    private val displays: Map<Int, WebDisplayChannel>,
    private val composedCluster: Boolean,
) {
    fun phoneConfig(type: Int, codec: VideoCodec, data: ByteArray) { if (!composedCluster || type != 111) displays[type]?.config(codec, data) }
    fun phoneFrame(type: Int, data: ByteArray, arrivalUs: Long) { if (!composedCluster || type != 111) displays[type]?.frame(data, arrivalUs) }
    fun phoneActive(type: Int, active: Boolean) { if (!composedCluster || type != 111) displays[type]?.setActive(active) }
    fun composedConfig(data: ByteArray) {
        displays[111]?.setActive(true)
        displays[111]?.config(VideoCodec.H264, data)
    }
    fun composedFrame(data: ByteArray, presentationUs: Long) { displays[111]?.frame(data, presentationUs) }
}
