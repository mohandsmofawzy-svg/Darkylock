package dev.mo.darkylock.modem

private val CFUN_AT_COMMAND = Regex("(?i)(?:^|[^A-Z0-9_])AT\\s*\\+\\s*CFUN\\b")

internal fun isCfunAtCommand(command: String): Boolean = CFUN_AT_COMMAND.containsMatchIn(command)
