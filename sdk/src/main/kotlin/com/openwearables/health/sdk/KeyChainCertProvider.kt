package com.openwearables.health.sdk

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.security.KeyChain
import android.util.Log
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/**
 * Pulls a client certificate from the Android system KeyChain at TLS
 * handshake time. The user installs the .p12 once via Android Settings
 * (Security → Install certificate from storage) and selects which alias the
 * app should use via [pickAlias]. The chosen alias is persisted in
 * SharedPreferences and survives app restarts.
 *
 * On every handshake, [KeyChainKeyManager] queries [KeyChain] for the live
 * private key and certificate chain — so cert rotation works without an app
 * rebuild: install a new cert in Settings, repeat [pickAlias], done.
 */
internal object KeyChainCertProvider {
    private const val TAG = "OWKeyChain"
    private const val PREFS_NAME = "open_wearables_keychain"
    private const val KEY_ALIAS = "client_cert_alias"

    fun storedAlias(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_ALIAS, null)

    fun storeAlias(context: Context, alias: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .also { if (alias.isNullOrEmpty()) it.remove(KEY_ALIAS) else it.putString(KEY_ALIAS, alias) }
            .apply()
    }

    /**
     * Prompts the user to choose one of the certificates installed in the
     * Android KeyChain. Must be called from the main thread; [callback] is
     * invoked on a KeyChain-internal thread with the user's choice (or null
     * if the user cancelled).
     */
    fun pickAlias(
        activity: Activity,
        hostHint: String? = null,
        callback: (alias: String?) -> Unit,
    ) {
        val uri = hostHint?.let { Uri.Builder().scheme("https").authority(it).build() }
        KeyChain.choosePrivateKeyAlias(
            activity,
            { alias ->
                if (alias != null) storeAlias(activity.applicationContext, alias)
                callback(alias)
            },
            null,   // any key types
            null,   // any issuers
            uri,
            null,   // no preselected alias
        )
    }

    fun buildKeyManager(context: Context, alias: String): X509ExtendedKeyManager =
        KeyChainKeyManager(context.applicationContext, alias)

    private class KeyChainKeyManager(
        private val context: Context,
        private val alias: String,
    ) : X509ExtendedKeyManager() {
        override fun chooseClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            socket: Socket?,
        ): String = alias

        override fun chooseEngineClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            engine: SSLEngine?,
        ): String = alias

        override fun getCertificateChain(requested: String?): Array<X509Certificate>? {
            if (requested != alias) return null
            return try {
                KeyChain.getCertificateChain(context, alias)
            } catch (t: Throwable) {
                Log.e(TAG, "KeyChain.getCertificateChain failed for alias=$alias", t)
                null
            }
        }

        override fun getPrivateKey(requested: String?): PrivateKey? {
            if (requested != alias) return null
            return try {
                KeyChain.getPrivateKey(context, alias)
            } catch (t: Throwable) {
                Log.e(TAG, "KeyChain.getPrivateKey failed for alias=$alias", t)
                null
            }
        }

        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> =
            arrayOf(alias)

        override fun chooseServerAlias(
            keyType: String?,
            issuers: Array<out Principal>?,
            socket: Socket?,
        ): String? = null

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    }
}
