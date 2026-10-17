package pl.czak.learnlauncher.data.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

actual fun createBackendHttpClient(): HttpClient = HttpClient(OkHttp)
