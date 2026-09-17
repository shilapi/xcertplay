package com.shilapi.xcertplay.airplay.rcs.caf

enum class CafTransactionRule {
    REQUIRED,
    OPTIONAL,
    FORBIDDEN,
}

enum class CafValueRule {
    NONE,
    WILDCARD_OR_IID_LIST,
    IID_LIST,
    IID_VALUE_MAP,
    CONFIG_TREE,
}

enum class CafErrorRule {
    NONE,
    IID_ERROR_MAP,
    OS_STATUS,
}

/**
 * CAF command catalog from the CarPlay Ultra firmware reference.
 */
enum class CafCommand(
    val wireName: String,
    val transactionRule: CafTransactionRule,
    val valueRule: CafValueRule,
    val errorRule: CafErrorRule,
) {
    CONFIG_REQUEST(
        "configRequest",
        CafTransactionRule.REQUIRED,
        CafValueRule.NONE,
        CafErrorRule.NONE,
    ),
    CONFIG_RESPONSE(
        "configResponse",
        CafTransactionRule.REQUIRED,
        CafValueRule.CONFIG_TREE,
        CafErrorRule.NONE,
    ),
    CONFIG_NOTIFY(
        "configNotify",
        CafTransactionRule.FORBIDDEN,
        CafValueRule.CONFIG_TREE,
        CafErrorRule.NONE,
    ),
    REGISTER_REQUEST(
        "registerRequest",
        CafTransactionRule.REQUIRED,
        CafValueRule.WILDCARD_OR_IID_LIST,
        CafErrorRule.NONE,
    ),
    REGISTER_RESPONSE(
        "registerResponse",
        CafTransactionRule.REQUIRED,
        CafValueRule.IID_VALUE_MAP,
        CafErrorRule.IID_ERROR_MAP,
    ),
    UNREGISTER_REQUEST(
        "unregisterRequest",
        CafTransactionRule.REQUIRED,
        CafValueRule.IID_LIST,
        CafErrorRule.NONE,
    ),
    UNREGISTER_RESPONSE(
        "unregisterResponse",
        CafTransactionRule.REQUIRED,
        CafValueRule.NONE,
        CafErrorRule.NONE,
    ),
    READ_REQUEST(
        "readRequest",
        CafTransactionRule.REQUIRED,
        CafValueRule.IID_LIST,
        CafErrorRule.NONE,
    ),
    READ_RESPONSE(
        "readResponse",
        CafTransactionRule.REQUIRED,
        CafValueRule.IID_VALUE_MAP,
        CafErrorRule.IID_ERROR_MAP,
    ),
    WRITE_REQUEST(
        "writeRequest",
        CafTransactionRule.REQUIRED,
        CafValueRule.IID_VALUE_MAP,
        CafErrorRule.NONE,
    ),
    WRITE_RESPONSE(
        "writeResponse",
        CafTransactionRule.REQUIRED,
        CafValueRule.NONE,
        CafErrorRule.IID_ERROR_MAP,
    ),
    CONTROL_REQUEST(
        "controlRequest",
        CafTransactionRule.REQUIRED,
        CafValueRule.IID_VALUE_MAP,
        CafErrorRule.NONE,
    ),
    CONTROL_RESPONSE(
        "controlResponse",
        CafTransactionRule.REQUIRED,
        CafValueRule.IID_VALUE_MAP,
        CafErrorRule.IID_ERROR_MAP,
    ),
    CONTROL_NOTIFY(
        "controlNotify",
        CafTransactionRule.FORBIDDEN,
        CafValueRule.IID_VALUE_MAP,
        CafErrorRule.NONE,
    ),
    UPDATE_NOTIFY(
        "updateNotify",
        CafTransactionRule.FORBIDDEN,
        CafValueRule.IID_VALUE_MAP,
        CafErrorRule.NONE,
    ),
    GENERAL_ERROR(
        "generalError",
        CafTransactionRule.OPTIONAL,
        CafValueRule.NONE,
        CafErrorRule.OS_STATUS,
    ),
    ;

    companion object {
        private val byWireName = entries.associateBy(CafCommand::wireName)

        fun find(wireName: String): CafCommand? = byWireName[wireName]

        fun require(wireName: String): CafCommand =
            requireNotNull(byWireName[wireName]) { "Unknown CAF command '$wireName'" }
    }
}

sealed interface CafRegistration {
    data object Wildcard : CafRegistration

    data class Identifiers(val values: List<Long>) : CafRegistration {
        init {
            require(values.isNotEmpty()) { "register identifiers must not be empty" }
        }
    }
}
