package dev.mo.darkylock.modem

import dev.mo.darkylock.data.PrivilegedClient
import org.json.JSONArray
import kotlin.time.Duration.Companion.seconds

object GamePriorityManager {
    private val packageNamePattern = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")

    suspend fun activate(
        packages: List<String>,
        access: Access?,
        client: PrivilegedClient,
    ) {
        check(access == Access.SHIZUKU_ADB || access == Access.SHIZUKU_ROOT) {
            "Game Priority requires an active Shizuku connection."
        }
        check(client.running) { "Shizuku is not connected. Reconnect and try again." }
        require(packages.isNotEmpty()) { "Enter at least one game package name." }
        require(packages.all(packageNamePattern::matches)) { "One or more package names are invalid." }

        val targets = packages.distinct()
        client.call("game.priority", (targets.size * 35L + 10L).seconds) {
            put("packageNames", JSONArray(targets))
        }
    }

    suspend fun setPerformanceMode(
        packages: List<String>,
        enabled: Boolean,
        access: Access?,
        client: PrivilegedClient,
    ) {
        check(access == Access.SHIZUKU_ADB || access == Access.SHIZUKU_ROOT) {
            "Drop-Fix requires an active Shizuku connection."
        }
        check(client.running) { "Shizuku is not connected. Reconnect and try again." }
        require(packages.isNotEmpty()) { "Enter at least one game package name." }
        require(packages.all(packageNamePattern::matches)) { "One or more package names are invalid." }

        val targets = packages.distinct()
        client.call("game.mode", (targets.size * 15L + 10L).seconds) {
            put("packageNames", JSONArray(targets))
            put("mode", if (enabled) "performance" else "standard")
        }
    }
}
