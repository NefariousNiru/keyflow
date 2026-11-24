package io.keyflow.remotekeyboard.keyboard

import android.annotation.TargetApi
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest
import java.util.regex.Pattern

/** Optional OS association. The transport pin remains the identity authority. */
internal class CompanionAssociation(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("keyboard-companion", Context.MODE_PRIVATE)

    fun supported(): Boolean = Build.VERSION.SDK_INT >= 33 &&
        app.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP) &&
        app.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)

    fun matches(pin: ByteArray?): Boolean =
        Build.VERSION.SDK_INT >= 33 && pin != null && supported() && matchesAssociation(pin)

    fun request(
        pin: ByteArray, bluetoothName: String, currentPin: () -> ByteArray?,
        askUser: (IntentSender) -> Unit, completed: (Boolean) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < 33 || !supported()) {
            completed(false)
            return
        }
        requestAssociation(pin, bluetoothName, currentPin, askUser, completed)
    }

    fun remove() {
        if (Build.VERSION.SDK_INT >= 33) {
            val id = preferences.getInt("id", -1)
            val manager = app.getSystemService(CompanionDeviceManager::class.java)
            if (id >= 0 && manager.myAssociations.any { it.id == id }) manager.disassociate(id)
        }
        preferences.edit().clear().apply()
    }

    @TargetApi(33)
    private fun matchesAssociation(pin: ByteArray): Boolean {
        val id = preferences.getInt("id", -1)
        return preferences.getString("pin", null) == hex(pin) && id >= 0 &&
            app.getSystemService(CompanionDeviceManager::class.java).myAssociations.any { it.id == id }
    }

    @TargetApi(33)
    private fun requestAssociation(
        pin: ByteArray, bluetoothName: String, currentPin: () -> ByteArray?,
        askUser: (IntentSender) -> Unit, completed: (Boolean) -> Unit
    ) {
        val manager = app.getSystemService(CompanionDeviceManager::class.java)
        val filter = BluetoothDeviceFilter.Builder()
            .setNamePattern(Pattern.compile(Pattern.quote(bluetoothName))).build()
        val request = AssociationRequest.Builder().addDeviceFilter(filter).build()
        try {
            manager.associate(request, app.mainExecutor, object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) = askUser(intentSender)
                override fun onAssociationCreated(associationInfo: AssociationInfo) {
                    val live = currentPin()
                    if (associationInfo.deviceMacAddress == null || live == null || !MessageDigest.isEqual(pin, live)) {
                        manager.disassociate(associationInfo.id)
                        completed(false)
                        return
                    }
                    val old = preferences.getInt("id", -1)
                    if (old >= 0 && old != associationInfo.id && manager.myAssociations.any { it.id == old })
                        manager.disassociate(old)
                    completed(preferences.edit().putInt("id", associationInfo.id).putString("pin", hex(pin)).commit())
                }
                override fun onFailure(error: CharSequence?) = completed(false)
            })
        } catch (_: SecurityException) {
            completed(false)
        } catch (_: IllegalArgumentException) {
            completed(false)
        }
    }

    private fun hex(pin: ByteArray): String = pin.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}
