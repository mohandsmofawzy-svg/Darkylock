package dev.mo.darkylock.shizuku

import android.content.Context
import dev.mo.darkylock.data.RootClient
import dev.mo.darkylock.data.RootException
import dev.mo.darkylock.modem.Access
import dev.mo.darkylock.modem.HelperStarter
import dev.mo.darkylock.modem.StartedHelper
import dev.mo.darkylock.modem.asClause
import kotlinx.coroutines.CancellationException

class RootOrShizuku(
    private val context: Context,
    private val onShizukuLost: () -> Unit,
) : HelperStarter {
    override suspend fun start(showStep: (String) -> Unit): StartedHelper {
        showStep("Asking for root access...")
        val rootClient = RootClient(context)
        val rootError =
            try {
                return StartedHelper(rootClient, rootClient.start(), Access.ROOT)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message?.asClause() ?: "root access was denied"
            }
        showStep("Looking for Shizuku...")
        if (!ShizukuClient.awaitBinder()) {
            val shizukuProblem =
                if (ShizukuClient.installedVersion(context) === null) {
                    "Shizuku isn't installed. Grant root, or install and start Shizuku, then try again."
                } else {
                    "Shizuku is installed but hasn't connected to Darky Lock, so it probably isn't running. Open Shizuku to check, then try again."
                }
            throw RootException("No root access ($rootError), and $shizukuProblem")
        }
        showStep("Connecting to Shizuku. Allow Darky Lock if it asks...")
        val shizukuClient = ShizukuClient(context, onShizukuLost)
        val hello =
            try {
                shizukuClient.start()
            } catch (e: CancellationException) {
                shizukuClient.stop()
                throw e
            } catch (e: Exception) {
                shizukuClient.stop()
                throw RootException(
                    "No root access ($rootError), and Shizuku didn't work either (${e.message?.asClause() ?: "no reason given"}).",
                )
            }
        val access = if (hello.optInt("uid", -1) == 0) Access.SHIZUKU_ROOT else Access.SHIZUKU_ADB
        val note = if (shizukuClient.inShell) "Shizuku's service for Darky Lock doesn't start on this phone, so the helper runs in a Shizuku shell." else null
        return StartedHelper(shizukuClient, hello, access, note)
    }
}
