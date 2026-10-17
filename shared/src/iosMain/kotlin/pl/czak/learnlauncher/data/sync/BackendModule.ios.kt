package pl.czak.learnlauncher.data.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

actual fun createBackendHttpClient(): HttpClient = HttpClient(Darwin)
