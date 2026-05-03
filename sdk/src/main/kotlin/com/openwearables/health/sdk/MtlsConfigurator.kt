package com.openwearables.health.sdk

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import java.io.InputStream
import java.security.KeyStore
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Installs a client certificate on [SyncManager.sharedHttpClient] for mTLS
 * endpoints. Two sources are supported, in priority order:
 *
 * 1. **Android KeyChain** (preferred). User installs a `.p12` once via
 *    Android Settings → Security → Install certificate from storage, then
 *    picks which alias to use via [OpenWearablesHealthSDK.pickClientCertificate].
 *    The cert never lives in the APK and rotation is just "install a new
 *    cert in Settings, re-pick".
 *
 * 2. **Bundled `.p12` in app assets** (fallback). Drop the `.p12` at
 *    `app/src/main/assets/openwearables_client.p12` (or in the Flutter assets
 *    bundle) with its password in a string resource named
 *    `openwearables_mtls_p12_password`. Useful for non-interactive builds.
 *
 * If neither is available, this is a silent no-op and the SDK uses plain
 * TLS on the configured host.
 */
internal object MtlsConfigurator {
    private const val TAG = "OWMtls"
    internal const val ASSET_NAME = "openwearables_client.p12"
    internal const val PASSWORD_RES_NAME = "openwearables_mtls_p12_password"

    private val CANDIDATE_ASSET_PATHS = listOf(
        ASSET_NAME,
        "flutter_assets/assets/$ASSET_NAME",
    )

    /**
     * Installs whichever cert source is configured, replacing any prior
     * configurator. Triggers a rebuild of [SyncManager.sharedHttpClient] so
     * the change takes effect immediately on the next request.
     */
    fun reload(context: Context) {
        val app = context.applicationContext
        val configurator = keyChainConfigurator(app) ?: assetsConfigurator(app)
        SyncManager.mtlsConfigurator = configurator
        SyncManager.rebuildSharedHttpClient()
    }

    private fun keyChainConfigurator(context: Context): ((OkHttpClient.Builder) -> Unit)? {
        val alias = KeyChainCertProvider.storedAlias(context) ?: return null
        return { builder ->
            try {
                val keyManager = KeyChainCertProvider.buildKeyManager(context, alias)
                applyKeyManager(builder, arrayOf(keyManager))
                Log.i(TAG, "mTLS client certificate installed from KeyChain (alias=$alias)")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to install mTLS client certificate from KeyChain", t)
            }
        }
    }

    private fun assetsConfigurator(context: Context): ((OkHttpClient.Builder) -> Unit)? {
        val passwordResId = context.resources.getIdentifier(
            PASSWORD_RES_NAME, "string", context.packageName
        )
        if (passwordResId == 0) return null
        val password = context.getString(passwordResId).takeIf { it.isNotBlank() } ?: return null

        val assetPath = CANDIDATE_ASSET_PATHS.firstOrNull { path ->
            try {
                context.assets.open(path).close()
                true
            } catch (_: Exception) {
                false
            }
        } ?: return null

        return { builder ->
            try {
                context.assets.open(assetPath).use { stream ->
                    applyP12(builder, stream, password.toCharArray())
                }
                Log.i(TAG, "mTLS client certificate installed from $assetPath")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to install mTLS client certificate from assets", t)
            }
        }
    }

    private fun applyP12(builder: OkHttpClient.Builder, p12: InputStream, password: CharArray) {
        val keyStore = KeyStore.getInstance("PKCS12").apply { load(p12, password) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, password)
        }
        applyKeyManager(builder, kmf.keyManagers)
    }

    private fun applyKeyManager(builder: OkHttpClient.Builder, keyManagers: Array<KeyManager>) {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(null as KeyStore?)
        }
        val trustManager = tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(keyManagers, arrayOf(trustManager), null)
        }
        builder.sslSocketFactory(sslContext.socketFactory, trustManager)
    }
}
