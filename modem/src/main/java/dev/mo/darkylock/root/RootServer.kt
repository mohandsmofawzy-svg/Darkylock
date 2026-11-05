/* Copyright (C) 2026 Mohamed Mahmoud (Darkylock). All rights reserved. */
package dev.mo.darkylock.root

import android.system.Os
import android.telephony.SubscriptionManager
import dev.mo.darkylock.core.toIntList
import dev.mo.darkylock.modem.isCfunAtCommand
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
- Started via su as:

-   CLASSPATH=<base.apk> app_process /system/bin dev.mo.darkylock.root.RootServer <nativeLibraryDir>

 *
- One JSON object per line on stdin and stdout:

-   request  {"id":1,"cmd":"qc.state","sim":1,...}

-   response {"id":1,"ok":true,"data":...} | {"id":1,"ok":false,"error":"..."}

 */
object RootServer {
    const val VERSION = 1

    private const val DEFAULT_AT_TIMEOUT_MS = 4000

    /** AT+EMMCHLCK mode 3: the lock holds in idle and in connected state (see [Mediatek]). */
    private const val MTK_CELL_LOCK_IDLE_AND_CONNECTED = 3

    @JvmStatic
    fun main(args: Array<String>) {
        val protocolOut = PrintStream(FileOutputStream(FileDescriptor.out), true, "UTF-8")
        // Keep stray framework prints away from the protocol stream.
        System.setOut(System.err)

        val nativeLibraryDir = args.getOrNull(0)
        protocolOut.println(hello(nativeLibraryDir).put("ready", true))

        val input = System.`in`.bufferedReader()
        // يقرأ أمر JSON واحدًا في كل سطر؛ exit ينهي المساعد بعد انتهاء العميل من عملياته.
        while (true) {
            val line = input.readLine() ?: break
            if (line.isBlank()) continue
            val command = runCatching { JSONObject(line).optString("cmd") }.getOrNull()
            if (command == "exit") break
            protocolOut.println(respond(line))
        }
        exitProcess(0)
    }

    private var libError: Throwable? = null
    private var libLoaded = false

    private fun loadNative(libDir: String?) {
        if (libLoaded || libDir.isNullOrEmpty()) return
        libError = runCatching { System.load("$libDir/libdarkyqrtr.so") }.exceptionOrNull()
        libLoaded = libError === null
    }

    private fun hello(libDir: String?): JSONObject {
        loadNative(libDir)
        return JSONObject()
            .put("version", VERSION)
            .put("uid", Os.getuid())
            .put("qrtrLib", libLoaded)
            .put("libError", libError?.message ?: JSONObject.NULL)
    }

    /** Handles one request line and returns the response line. Shared by the su helper and the Shizuku service. */
    @Synchronized
    fun respond(line: String): String {
        val response = JSONObject()
        try {
            val request = JSONObject(line)
            response.put("id", request.optInt("id"))
            val data = if (request.optString("cmd") == "hello") hello(request.optString("libDir")) else handle(request)
            response.put("data", data ?: JSONObject.NULL).put("ok", true)
        } catch (error: Throwable) {
            response.put("ok", false).put("error", error.message ?: error.javaClass.simpleName)
        }
        return response.toString()
    }

    private fun JSONObject.isPresent(key: String): Boolean = has(key) && !isNull(key)

    private fun JSONObject.ints(key: String): List<Int> = optJSONArray(key)?.toIntList() ?: emptyList()

    private fun JSONObject.intsOrNull(key: String): List<Int>? = if (isPresent(key)) ints(key) else null

    private fun JSONObject.intOrNull(key: String): Int? = if (isPresent(key)) getInt(key) else null

    private fun handle(request: JSONObject): Any? {
        val sim = request.optInt("sim", 1)
        val simSlot = sim - 1
        val requestedSubId = request.optInt("subId", SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        val subId = Aosp.resolveSubId(requestedSubId, simSlot)
        val persist = request.optBoolean("persist", true)
        return when (val command = request.getString("cmd")) {
            "ping" -> JSONObject().put("uid", Os.getuid()).put("version", VERSION)

            "detect" -> Sys.detect()

            "network.snapshot" -> Sys.networkSnapshot()

            "game.priority" -> gamePriority(request.optJSONArray("packageNames") ?: JSONArray())

            // أمر مخصص لتنفيذ وضع الأداء وتحديد الإطارات بشكل دوري وسريع (Drop-Fix)
            "game.performance.mode" -> gamePerformanceMode(
                request.optJSONArray("packageNames") ?: JSONArray()
            )

            "game.mode" -> gameMode(
                request.optJSONArray("packageNames") ?: JSONArray(),
                request.getString("mode"),
            )

            "aosp.state" -> Aosp.state(subId)

            "aosp.carriers" -> PhysicalChannels.report(simSlot)

            "aosp.setAllowed" -> Aosp.setAllowed(subId, request.getLong("mask"), request.optInt("slot", simSlot))

            "aosp.reapplyAllowed" -> Aosp.reapplyAllowed(subId, request.getLong("mask"))

            "aosp.setNrDc" -> Aosp.setNrDc(subId, request.getInt("state"))

            "aosp.setChannels" -> Aosp.setChannels(subId, request.optJSONArray("specs") ?: JSONArray(), request.optBoolean("split", true))

            "aosp.setPlmn" -> Aosp.setPlmn(subId, request.getString("plmn"), persist)

            "aosp.setPlmnAuto" -> Aosp.setPlmnAutomatic(subId)

            "aosp.rebootModem" -> {
                val slot = request.optInt("slot", simSlot)
                Aosp.rebootModem(slot, Aosp.resolveSubId(requestedSubId, slot))
            }

            "radio.cycle" -> Aosp.radioCycle()

            "radio.cycleSim" -> {
                check(subId >= 0) { "This SIM's subscription isn't known." }
                Aosp.cycleSubscriberRadio(subId)
            }

            "qc.state" -> Qualcomm.state(sim)

            "qc.serving" -> Qualcomm.serving(sim)

            "qc.setBands" -> Qualcomm.setBands(sim, request.getString("kind"), request.ints("bands"), persist)

            "qc.reset" -> Qualcomm.reset(sim, persist)

            "qc.setRat" -> Qualcomm.setRat(sim, request.getInt("mask"), persist)

            "qc.setNrMode" -> Qualcomm.setNrMode(sim, request.getInt("mode"), persist)

            "qc.setPlmn" -> Qualcomm.setPlmn(sim, request.getInt("mcc"), request.getInt("mnc"), request.optBoolean("threeDigit"), persist)

            "qc.clearPlmn" -> Qualcomm.clearPlmn(sim, persist)

            "qc.lteLock" -> Qualcomm.lteLock(sim, request.getLong("earfcn"), request.ints("pcis"))

            "qc.lteUnlock" -> Qualcomm.lteUnlock(sim)

            "qc.nrLock" -> Qualcomm.nrLock(sim, request)

            "qc.nrUnlock" -> Qualcomm.nrUnlock(sim)

            "qc.reboot" -> Qualcomm.reboot()

            // A failed QMI detach/attach falls back to power-cycling the radio through ITelephony, but only when the subscription is known.
            "qc.reattach" ->
                try {
                    Qualcomm.reattach(sim)
                } catch (e: Exception) {
                    if (subId < 0) throw e
                    Aosp.cycleSubscriberRadio(subId).put("qmiError", e.message)
                }

            "qc.raw" -> Qualcomm.raw(sim, request.getInt("service"), request.getInt("msg"), request.optString("payload").unhex())

            "mtk.findNode" -> JSONObject().put("node", Mediatek.findNode(sim) ?: JSONObject.NULL)

            "mtk.state" -> Mediatek.state(request.getString("node"))

            "mtk.setBands" ->
                Mediatek.setBands(
                    request.getString("node"),
                    request.intsOrNull("gsm"),
                    request.intsOrNull("wcdma"),
                    request.intsOrNull("lte"),
                    request.intsOrNull("nr"),
                )

            "mtk.cellLock" ->
                Mediatek.cellLock(
                    request.getString("node"),
                    request.getInt("act"),
                    request.getLong("arfcn"),
                    request.intOrNull("pci"),
                    request.optInt("mode", MTK_CELL_LOCK_IDLE_AND_CONNECTED),
                )

            "mtk.cellUnlock" -> Mediatek.cellUnlock(request.getString("node"))

            "mtk.setRat" -> Mediatek.setRat(request.getString("node"), request.getLong("mask"))

            "pixel.state" -> Pixel.state(sim)

            "pixel.setLte" -> Pixel.setLte(request.ints("bands"))

            "pixel.setNr" -> Pixel.setNr(request.ints("bands"))

            "pixel.setNrMode" -> Pixel.setNrMode(sim, request.getString("value"))

            "samsung.menu" -> Sys.openSamsungMenu()

            "samsung.nrMode" -> Samsung.nrMode(simSlot)

            "samsung.setNrMode" -> Samsung.setNrMode(simSlot, request.getInt("mode"))

            "at" -> {
                val timeoutMs = request.optInt("timeout", DEFAULT_AT_TIMEOUT_MS)
                val node = request.getString("node")
                val atCommand = request.getString("command")
                require(!isCfunAtCommand(atCommand)) { "AT+CFUN commands are disabled." }
                val reply = At.send(node, atCommand, timeoutMs)
                JSONObject().put("reply", reply)
            }

            else -> throw IllegalArgumentException("Unknown command: $command.")
        }
    }

    private fun gamePriority(packageNames: JSONArray): JSONObject {
        val packages = (0 until packageNames.length()).map { packageNames.getString(it) }.distinct()
        require(packages.isNotEmpty()) { "Enter at least one game package name." }
        require(packages.all(GAME_PACKAGE_NAME::matches)) { "One or more package names are invalid." }

        val packageUids =
            runCommand("/system/bin/pm", "list", "packages", "-U")
                .lineSequence()
                .mapNotNull { PACKAGE_UID.find(it)?.destructured?.let { (name, uid) -> name to uid } }
                .toMap()
        require(packages.all(packageUids::containsKey)) { "One or more selected game packages aren't installed." }
        val selected = packages.toSet()
        val selectedUids = packages.mapNotNull(packageUids::get).toSet()
        val otherPackagesSharingUid =
            packageUids.filter { (name, uid) -> uid in selectedUids && name !in selected }.keys
        check(otherPackagesSharingUid.isEmpty()) {
            "Refusing to affect other apps sharing the selected game UID: ${otherPackagesSharingUid.sorted().joinToString()}."
        }

        packages.forEach { packageName ->
            runAm("set-standby-bucket", packageName, "active")
            runCommand("cmd", "deviceidle", "whitelist", "+$packageName")

            // أ. مخاطبة مدير الألعاب الداخلي للنظام لفرض وضع الأداء وتحديد 90 FPS
           runCommand(executable = "cmd", arguments = arrayOf("game", "set","--mode","2","--fps", "90", packageName))

            // هـ. فرض أقصى معدل تحديث عام للشاشة (Peak Refresh Rate)
        //    runCommand(executable = "settings", arguments = arrayOf("put", "global", "peak_refresh_rate", "120.0"))
          //  runCommand(executable = "settings", arguments = arrayOf("put", "global", "min_refresh_rate", "120.0"))

            android.util.Log.d("GamePriority", "Successfully applied priority for: $packageName")
        }
        return JSONObject().put("packageNames", JSONArray(packages))
    }

    /**
     * دالة خفيفة ومستقلة لتنفيذ أمر الأداء والإطارات فقط (Drop-Fix)
     * لكي يتم تكرارها كل 10 ثوانٍ بكفاءة عالية ودون إهدار للموارد في تكرار الأوامر الثقيلة الأخرى.
     */
    private fun gamePerformanceMode(packageNames: JSONArray): JSONObject {
        val packages = (0 until packageNames.length()).map { packageNames.getString(it) }.distinct()
        require(packages.isNotEmpty()) { "Enter at least one game package name." }
        require(packages.all(GAME_PACKAGE_NAME::matches)) { "One or more package names are invalid." }

        packages.forEach { packageName ->
            // تنفيذ أمر الأداء وتثبيت الـ 90 إطار لكل حزمة بشكل مباشر ودوري
            runCommand(executable = "cmd", arguments = arrayOf("game", "set","--mode","2","--fps", "90", packageName))
         }
        return JSONObject().put("packageNames", JSONArray(packages))
    }

    private fun gameMode(
        packageNames: JSONArray,
        mode: String,
    ): JSONObject {
        require(mode == "performance" || mode == "standard") { "Unsupported Android game mode." }
        val packages = (0 until packageNames.length()).map { packageNames.getString(it) }.distinct()
        require(packages.isNotEmpty()) { "Enter at least one game package name." }
        require(packages.all(GAME_PACKAGE_NAME::matches)) { "One or more package names are invalid." }

        val installedPackages =
            runCommand("/system/bin/pm", "list", "packages")
                .lineSequence()
                .mapNotNull { line -> line.removePrefix("package:").takeIf { it != line } }
                .toSet()
        require(packages.all(installedPackages::contains)) { "One or more selected game packages aren't installed." }

        packages.forEach { packageName ->
            runCommand("cmd", "game", "mode", mode, packageName)
        }
        return JSONObject().put("packageNames", JSONArray(packages)).put("mode", mode)
    }

    private fun runAm(vararg arguments: String) {
        runCommand("/system/bin/am", *arguments)
    }

    private fun runCommand(
        executable: String,
        vararg arguments: String,
    ): String {
        val process = ProcessBuilder(executable, *arguments).redirectErrorStream(true).start()
        val output = ByteArrayOutputStream()
        val reader = thread(name = "darky-game-priority-output", isDaemon = true) {
            runCatching { process.inputStream.copyTo(output) }
        }
        if (!process.waitFor(GAME_PRIORITY_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            reader.join(OUTPUT_DRAIN_TIMEOUT_MS)
            throw IllegalStateException("${executable.substringAfterLast('/')} ${arguments.first()} timed out.")
        }
        reader.join(OUTPUT_DRAIN_TIMEOUT_MS)
        val message = output.toString(Charsets.UTF_8.name()).trim()
        check(process.exitValue() == 0) {
            "${executable.substringAfterLast('/')} ${arguments.first()} failed${message.takeIf(String::isNotEmpty)?.let { ": $it" }.orEmpty()}"
        }
        return message
    }

    private val GAME_PACKAGE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
    private val PACKAGE_UID = Regex("^package:(\\S+)\\s+uid:(\\d+)$")

    private const val GAME_PRIORITY_COMMAND_TIMEOUT_SECONDS = 15L
    private const val OUTPUT_DRAIN_TIMEOUT_MS = 1_000L
}