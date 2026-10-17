package pl.czak.learnlauncher.data.sync

import pl.czak.learnlauncher.data.auth.AuthManager
import pl.czak.learnlauncher.data.sync.ports.RemoteAuthProvider

class AuthManagerAuthProvider(
    private val authManager: AuthManager,
) : RemoteAuthProvider {
    override suspend fun headers(): Map<String, String> {
        val token = authManager.getToken() ?: return emptyMap()
        return mapOf("Authorization" to "Bearer $token")
    }

    override suspend fun refresh(): Boolean = false
}
