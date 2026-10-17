package pl.czak.learnlauncher.data.sync

import io.ktor.client.HttpClient
import pl.czak.learnlauncher.data.sync.adapter.supabase.SupabaseUploaderAdapter
import pl.czak.learnlauncher.data.sync.adapter.turso.TursoUploaderAdapter
import pl.czak.learnlauncher.data.sync.ports.PayloadUploader

/**
 * Per-platform Ktor engine selection lives in [createBackendHttpClient]
 * (expect/actual). Composition roots get an [HttpClient] without ever
 * importing `io.ktor.*` themselves — the engine choice is a `:shared`
 * detail.
 */
expect fun createBackendHttpClient(): HttpClient

/**
 * Composition root — single function the per-platform wiring code calls to
 * produce a [PayloadUploader] from configuration. The only place outside an
 * adapter that names concrete adapter classes.
 *
 * Priority when [BackendConfig.provider] is null/blank:
 *   1. Turso  (default — managed cloud)
 *   2. TCP    (self-hosted queue, via [tcpFactory] callback supplied by the
 *              platform composition root since the TCP adapter is JVM-bound)
 *   3. Supabase (tertiary cloud option)
 */
data class BackendConfig(
    val provider: String? = null,
    val tursoUrl: String? = null,
    val tursoAuthToken: String? = null,
    val supabaseUrl: String? = null,
    val supabaseAnonKey: String? = null,
    val tcpAvailable: Boolean = false,
)

fun selectUploader(
    config: BackendConfig,
    httpClient: HttpClient,
    tcpFactory: (() -> PayloadUploader)? = null,
): PayloadUploader? {
    val provider = config.provider?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    if (provider != null) {
        return when (provider) {
            "turso" -> tursoOrNull(config, httpClient)
            "tcp" -> tcpFactory?.invoke()
            "supabase" -> supabaseOrNull(config, httpClient)
            else -> null
        }
    }
    // Auto: turso → tcp → supabase.
    return tursoOrNull(config, httpClient)
        ?: tcpFactory?.invoke()
        ?: supabaseOrNull(config, httpClient)
}

private fun tursoOrNull(config: BackendConfig, httpClient: HttpClient): PayloadUploader? {
    val url = config.tursoUrl?.takeIf { it.isNotBlank() } ?: return null
    val token = config.tursoAuthToken?.takeIf { it.isNotBlank() } ?: return null
    return TursoUploaderAdapter(httpClient, url, token)
}

private fun supabaseOrNull(config: BackendConfig, httpClient: HttpClient): PayloadUploader? {
    val url = config.supabaseUrl?.takeIf { it.isNotBlank() } ?: return null
    val key = config.supabaseAnonKey?.takeIf { it.isNotBlank() } ?: return null
    return SupabaseUploaderAdapter(httpClient, url, key)
}
