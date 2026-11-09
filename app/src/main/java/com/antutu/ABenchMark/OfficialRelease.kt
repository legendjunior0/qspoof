package com.antutu.ABenchMark

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

internal object OfficialRelease {
    fun hasValidSignature(context: Context): Boolean {
        if (BuildConfig.DEBUG) return true

        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        )
        val signers = packageInfo.signingInfo?.apkContentsSigners ?: return false
        return signers.any { signature ->
            val fingerprint = MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { byte -> "%02X".format(byte) }
            fingerprint == BuildConfig.RELEASE_CERT_SHA256
        }
    }
}
