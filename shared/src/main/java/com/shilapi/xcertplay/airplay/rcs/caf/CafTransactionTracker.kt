package com.shilapi.xcertplay.airplay.rcs.caf

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class CafPendingTransaction(
    val pluginId: Long,
    val transactionId: Long,
    val requestCommand: CafCommand,
    val expectedResponses: Set<CafCommand>,
)

/**
 * Correlates CAF request/response transactions independently of the stream thread.
 */
class CafTransactionTracker(
    firstTransactionId: Long = 1L,
) {
    private val nextTransactionId = AtomicLong(firstTransactionId)
    private val pending = ConcurrentHashMap<Long, CafPendingTransaction>()

    init {
        require(firstTransactionId > 0) { "CAF transaction IDs must start above zero" }
    }

    fun begin(
        pluginId: Long,
        requestCommand: CafCommand,
        expectedResponses: Set<CafCommand>,
    ): CafPendingTransaction {
        require(requestCommand.transactionRule == CafTransactionRule.REQUIRED) {
            "${requestCommand.wireName} is not a transaction request"
        }
        require(expectedResponses.isNotEmpty()) {
            "${requestCommand.wireName} must declare at least one expected response"
        }
        while (true) {
            val transactionId = nextTransactionId.getAndUpdate { current ->
                if (current == Long.MAX_VALUE) 1L else current + 1L
            }
            val transaction = CafPendingTransaction(
                pluginId = pluginId,
                transactionId = transactionId,
                requestCommand = requestCommand,
                expectedResponses = expectedResponses,
            )
            if (pending.putIfAbsent(transactionId, transaction) == null) {
                return transaction
            }
        }
    }

    fun accept(reader: CafMessageReader): CafPendingTransaction? {
        val transactionId = reader.transactionId ?: return null
        val transaction = pending[transactionId] ?: return null
        if (reader.pluginId != transaction.pluginId) {
            throw CafProtocolException(
                "CAF transaction $transactionId belongs to plugin ${transaction.pluginId}, " +
                    "not ${reader.pluginId}",
            )
        }
        if (reader.command !in transaction.expectedResponses) {
            throw CafProtocolException(
                "CAF transaction $transactionId expected ${transaction.expectedResponses
                    .joinToString { it.wireName }}, got ${reader.command.wireName}",
            )
        }
        pending.remove(transactionId, transaction)
        return transaction
    }

    fun cancel(transaction: CafPendingTransaction): Boolean =
        pending.remove(transaction.transactionId, transaction)

    fun size(): Int = pending.size
}
