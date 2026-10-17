package pl.czak.learnlauncher.data.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

actual fun createBackendHttpClient(): HttpClient = HttpClient(CIO)
