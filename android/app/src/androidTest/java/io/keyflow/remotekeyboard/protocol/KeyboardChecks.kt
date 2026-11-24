package io.keyflow.remotekeyboard.protocol

import android.app.Instrumentation
import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import io.keyflow.remotekeyboard.KeyboardTestService
import io.keyflow.remotekeyboard.keyboard.ActivationPolicy
import io.keyflow.remotekeyboard.keyboard.FocusToken
import io.keyflow.remotekeyboard.keyboard.InputMailbox
import io.keyflow.remotekeyboard.keyboard.KeyboardInput
import io.keyflow.remotekeyboard.keyboard.KeyboardRuntime
import io.keyflow.remotekeyboard.keyboard.KeyboardState
import io.keyflow.remotekeyboard.keyboard.ModifierMode
import io.keyflow.remotekeyboard.keyboard.RemoteKey
import io.keyflow.remotekeyboard.keyboard.unicodeScalar
import io.keyflow.remotekeyboard.network.LanStatus
import io.keyflow.remotekeyboard.network.WifiLink
import io.keyflow.remotekeyboard.network.WifiMonitor
import io.keyflow.remotekeyboard.network.WifiPreferences
import io.keyflow.remotekeyboard.network.parseManualAddress
import io.keyflow.remotekeyboard.pairing.AndroidTrustStore
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object KeyboardChecks {
    fun models() {
        val state = KeyboardState()
        val ctrl = RemoteKey.Control
        check(RemoteKey.F12.wire == 29 && RemoteKey.A.wire == 30 && RemoteKey.Slash.wire == 77)
        state.toggle(ctrl)
        check(state.mode(ctrl) == ModifierMode.NEXT)
        check(state.press(RemoteKey.C) == listOf(
            KeyboardInput.Key(ctrl, true), KeyboardInput.Key(RemoteKey.C, true),
            KeyboardInput.Key(RemoteKey.C, false), KeyboardInput.Key(ctrl, false)))
        check(state.mode(ctrl) == ModifierMode.OFF)
        state.toggle(ctrl)
        state.toggle(ctrl)
        check(state.mode(ctrl) == ModifierMode.LOCKED)
        check(state.press(RemoteKey.Left).first() == KeyboardInput.Key(ctrl, true))
        check(state.press(RemoteKey.Right).last() == KeyboardInput.Key(ctrl, false))
        state.clear()
        check(state.mode(ctrl) == ModifierMode.OFF)
        state.beginModifier(ctrl)
        check(state.press(RemoteKey.Left).first() == KeyboardInput.Key(ctrl, true))
        state.endModifier(ctrl, true)
        check(state.mode(ctrl) == ModifierMode.OFF)
        check(unicodeScalar("1F642") == "\uD83D\uDE42")
        check(unicodeScalar("D800") == null && unicodeScalar("110000") == null && unicodeScalar("0A") == null)

        val token = FocusToken(UUID.randomUUID(), 1)
        val next = token.copy(generation = 2)
        val input = InputMailbox()
        val item = listOf(KeyboardInput.Key(RemoteKey.C, true))
        check(input.offer(token, item) == InputMailbox.Result.STALE)
        input.setFocus(token)
        check(input.offer(token, item) == InputMailbox.Result.SCHEDULE)
        val inFlight = requireNotNull(input.next())
        check(input.release(token) == InputMailbox.Result.QUEUED)
        check(!input.accepts(token, inFlight.third))
        check(input.next()?.second == listOf(KeyboardInput.Release))
        check(input.next() == null)
        input.setFocus(next)
        check(input.offer(token, item) == InputMailbox.Result.STALE)
        repeat(8) { check(input.offer(next, item) != InputMailbox.Result.OVERFLOW) }
        check(input.offer(next, item) == InputMailbox.Result.OVERFLOW)
        check(input.next() == null)

        val policy = ActivationPolicy()
        check(policy.update(token) && !policy.update(token))
        check(policy.dismiss(token) && !policy.permits(token, false) && policy.permits(token, true))
        policy.update(null)
        check(!policy.update(token) && policy.update(next))
        check(!policy.permits(token, true) && policy.permits(next, false))
    }

    fun physical(
        test: Instrumentation, mode: String, namespace: String, address: String, otp: String?, report: (String) -> Unit
    ) {
        require(mode in setOf("keyboard", "beforeForceStop", "afterForceStop"))
        require(Regex("keyflow-test-[0-9a-f]{16}").matches(namespace))
        val context = test.targetContext
        check(!context.getSystemService(KeyguardManager::class.java).isDeviceLocked) { "Unlock the device to start this test." }
        val preferenceName = "$namespace.wifi"
        val preferences = WifiPreferences(context, preferenceName)
        val available = CountDownLatch(1)
        var wifi: WifiLink? = null
        WifiMonitor(context) { link ->
            if (link != null) {
                wifi = link
                available.countDown()
            }
        }.use { check(available.await(10, TimeUnit.SECONDS)) { "Missing trusted Wi-Fi metadata." } }
        val link = requireNotNull(wifi)
        preferences.save(link.identity)
        preferences.saveManual(requireNotNull(parseManualAddress(address)))
        val service = Intent(context, KeyboardTestService::class.java).putExtra("trustNamespace", namespace)
        val ui = test.uiAutomation
        val previousRotation = shell(ui, "settings get system user_rotation").trim().toIntOrNull() ?: 0
        val automaticRotation = shell(ui, "settings get system accelerometer_rotation").trim() == "1"
        var rotationChanged = false
        var needsWake = false
        try {
            test.runOnMainSync {
                KeyboardRuntime.setupVisible = true
                context.startForegroundService(service)
            }
            if (mode != "afterForceStop") {
                await("trusted Wi-Fi and pairing readiness") {
                    onMain(test) { KeyboardRuntime.service != null && KeyboardRuntime.status == LanStatus.PAIR_REQUIRED }
                }
                test.runOnMainSync {
                    val code = requireNotNull(otp).toCharArray()
                    try { KeyboardRuntime.service?.pair(code) } finally { code.fill(0.toChar()) }
                }
            }
            await("authenticated connection") { onMain(test) { KeyboardRuntime.status == LanStatus.CONNECTED } }
            report("M3_CONNECTED")
            if (mode == "beforeForceStop") {
                await("external force-stop", 30_000) { false }
                return
            }
            if (mode == "afterForceStop") return

            await("foreground keyboard") { find(ui, "Q") != null }
            tap(ui, "Q")
            tap(ui, "Copy")
            tap(ui, "Fn")
            tap(ui, "F12")
            tap(ui, "ABC")
            val back = Rect()
            await("Backspace bounds") {
                val node = find(ui, "Backspace") ?: return@await false
                node.getBoundsInScreen(back)
                !back.isEmpty
            }
            val x = back.centerX()
            val y = back.centerY()
            shell(ui, "input touchscreen swipe $x $y $x $y 780")
            SystemClock.sleep(200)
            tap(ui, "Unicode")
            tap(ui, "é")
            for (digit in "1F642") tap(ui, "Hex $digit")
            tap(ui, "Send character")

            rotationChanged = ui.setRotation(UiAutomation.ROTATION_FREEZE_90)
            check(rotationChanged) { "The device did not accept landscape rotation." }
            await("landscape configuration") {
                context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            }
            await("keyboard after landscape rotation") { find(ui, "Dismiss") != null }
            tap(ui, "Dismiss")
            await("dismissed keyboard") { find(ui, "Dismiss") == null }
            SystemClock.sleep(950)
            check(find(ui, "Dismiss") == null)
            report("M3_DISMISSED")

            test.runOnMainSync { KeyboardRuntime.setupVisible = false }
            shell(ui, "input keyevent KEYCODE_HOME")
            report("M3_BACKGROUND")
            await("new authenticated focus in background") {
                onMain(test) { KeyboardRuntime.focus?.generation?.let { it >= 4 } == true }
            }
            SystemClock.sleep(950)
            check(find(ui, "Dismiss") == null) { "A background request bypassed the fallback policy." }
            shell(ui, "cmd statusbar expand-notifications")
            await("fallback notification") { find(ui, "Desktop keyboard is ready") != null }
            tap(ui, "Desktop keyboard is ready")
            await("notification opened keyboard") { find(ui, "Dismiss") != null }
            report("M3_FALLBACK_OK")

            val oldFocus = onMainValue(test) { requireNotNull(KeyboardRuntime.focus) }
            try {
                shell(ui, "svc wifi disable")
                await("keyboard hidden on Wi-Fi loss") {
                    onMain(test) { KeyboardRuntime.focus == null && KeyboardRuntime.status != LanStatus.CONNECTED } &&
                        find(ui, "Dismiss") == null
                }
            } finally {
                shell(ui, "svc wifi enable")
            }
            await("authenticated Wi-Fi recovery without re-pairing", 30_000) {
                onMain(test) { KeyboardRuntime.status == LanStatus.CONNECTED && KeyboardRuntime.focus == null }
            }
            check(!onMain(test) {
                KeyboardRuntime.service?.send(oldFocus, listOf(KeyboardInput.Key(RemoteKey.Q, true))) == true
            }) { "Input from a pre-interruption focus was accepted." }
            report("M3_RECOVERED")

            val power = context.getSystemService(PowerManager::class.java)
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            needsWake = true
            shell(ui, "input keyevent KEYCODE_SLEEP")
            await("screen off") { !power.isInteractive }
            report("M3_SCREEN_OFF")
            await("authenticated focus while screen off") { onMain(test) { KeyboardRuntime.focus != null } }
            val backgroundFocus = onMainValue(test) { requireNotNull(KeyboardRuntime.focus) }
            check(!power.isInteractive && !onMain(test) {
                KeyboardRuntime.service?.send(backgroundFocus, listOf(KeyboardInput.Key(RemoteKey.Q, true))) == true
            }) { "Screen-off focus woke the device or accepted input." }
            try {
                shell(ui, "cmd deviceidle force-idle deep")
                await("forced deep Doze") { power.isDeviceIdleMode }
                check(!power.isInteractive && !onMain(test) {
                    KeyboardRuntime.service?.send(backgroundFocus, listOf(KeyboardInput.Key(RemoteKey.Q, true))) == true
                }) { "Doze woke the device or accepted input." }
                report("M3_DOZE_OK")
            } finally {
                shell(ui, "cmd deviceidle unforce")
                shell(ui, "input keyevent KEYCODE_WAKEUP")
                needsWake = false
            }
            await("display awake after Doze") { power.isInteractive }
            if (keyguard.isDeviceSecure) {
                await("secure keyguard after wake") { keyguard.isKeyguardLocked && keyguard.isDeviceLocked }
                check(!onMain(test) {
                    KeyboardRuntime.service?.send(backgroundFocus, listOf(KeyboardInput.Key(RemoteKey.Q, true))) == true
                }) { "Secure keyguard accepted keyboard input." }
                report("M3_KEYGUARD_OK")
            }
        } finally {
            if (needsWake) shell(ui, "input keyevent KEYCODE_WAKEUP")
            if (rotationChanged) ui.setRotation(if (automaticRotation) UiAutomation.ROTATION_UNFREEZE else previousRotation)
            shell(ui, "cmd statusbar collapse")
            test.runOnMainSync {
                KeyboardRuntime.setupVisible = false
                context.stopService(service)
            }
            await("stopped service") { onMain(test) { KeyboardRuntime.service == null } }
            AndroidTrustStore(context, namespace).clear()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("$namespace.identity.v1")
            context.deleteSharedPreferences(preferenceName)
        }
    }

    private fun find(ui: UiAutomation, value: String): AccessibilityNodeInfo? {
        val root = ui.rootInActiveWindow ?: return null
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (node.text?.toString() == value || node.contentDescription?.toString() == value) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let(pending::add)
        }
        return null
    }

    private fun tap(ui: UiAutomation, label: String) {
        val bounds = Rect()
        await(label) {
            val node = find(ui, label) ?: return@await false
            node.getBoundsInScreen(bounds)
            !bounds.isEmpty
        }
        shell(ui, "input tap " + bounds.centerX() + " " + bounds.centerY())
        SystemClock.sleep(100)
    }

    private fun shell(ui: UiAutomation, command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun onMain(test: Instrumentation, predicate: () -> Boolean): Boolean {
        var result = false
        test.runOnMainSync { result = predicate() }
        return result
    }

    private fun <T : Any> onMainValue(test: Instrumentation, read: () -> T): T {
        var result: T? = null
        test.runOnMainSync { result = read() }
        return requireNotNull(result)
    }

    private fun await(label: String, timeout: Long = 12_000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        error("Timed out waiting for $label")
    }
}
