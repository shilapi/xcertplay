package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandler
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType

/**
 * Runtime-owned Ultra capability state shared by AirPlay /info negotiation and type-130 dispatch.
 *
 * A sidecar alone never enables a feature. [AirPlayUltraConfig.readyFeatures] still controls the
 * local feature opt-in, and every ready feature must additionally have its confirmed sidecar and
 * a handler for each required RCS client type.
 */
class AirPlayUltraRuntime private constructor(
    private val infoSidecars: Map<AirPlayFeature, Any?>,
    private val rcsFactories: Map<RcsClientType, RcsDataStreamHandlerFactory>,
    private val runtimeFeatures: Set<AirPlayFeature>,
) : RcsDataStreamHandlerFactory {
    fun resolveInfoSidecar(feature: AirPlayFeature): Any? = infoSidecars[feature]

    fun supportsFeature(feature: AirPlayFeature): Boolean = feature in runtimeFeatures

    fun supportsRcsClientType(clientType: RcsClientType): Boolean =
        rcsFactories[clientType]?.supports(clientType) == true

    override fun supports(clientType: RcsClientType): Boolean =
        supportsRcsClientType(clientType)

    override fun create(stream: RcsDataStream): RcsDataStreamHandler? =
        rcsFactories[stream.clientType]?.create(stream)

    class Builder {
        private val infoSidecars = linkedMapOf<AirPlayFeature, Any?>()
        private val rcsFactories = linkedMapOf<RcsClientType, RcsDataStreamHandlerFactory>()
        private val runtimeFeatures = linkedSetOf<AirPlayFeature>()

        fun sidecar(feature: AirPlayFeature, value: Any?): Builder = apply {
            require(feature.requiresInfoResponseSidecar) {
                "AirPlay feature '${feature.wireName}' does not use an /info sidecar"
            }
            require(!infoSidecars.containsKey(feature)) {
                "AirPlay sidecar '${feature.infoResponseKey}' is already registered"
            }
            infoSidecars[feature] = value
        }

        fun rcs(
            clientTypes: Iterable<RcsClientType>,
            factory: RcsDataStreamHandlerFactory,
        ): Builder = apply {
            val types = clientTypes.toList()
            require(types.isNotEmpty()) { "At least one RCS client type is required" }
            types.forEach { clientType ->
                require(!rcsFactories.containsKey(clientType)) {
                    "RCS handler for '${clientType.name}' is already registered"
                }
                rcsFactories[clientType] = factory
            }
        }

        fun runtimeFeature(feature: AirPlayFeature): Builder = apply {
            runtimeFeatures += feature
        }

        fun build(): AirPlayUltraRuntime = AirPlayUltraRuntime(
            infoSidecars = LinkedHashMap(infoSidecars),
            rcsFactories = LinkedHashMap(rcsFactories),
            runtimeFeatures = LinkedHashSet(runtimeFeatures),
        )
    }

    companion object {
        fun builder(): Builder = Builder()

        fun empty(): AirPlayUltraRuntime = Builder().build()
    }
}
