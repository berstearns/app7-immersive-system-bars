package pl.czak.learnlauncher.data.sync.ports

interface RemoteAuthProvider {
    suspend fun headers(): Map<String, String>
    suspend fun refresh(): Boolean
}
