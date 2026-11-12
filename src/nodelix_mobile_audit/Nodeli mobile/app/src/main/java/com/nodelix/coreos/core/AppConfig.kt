package com.nodelix.coreos.core

import com.nodelix.coreos.BuildConfig

object AppConfig {
    const val APPLICATION_ID: String = BuildConfig.APPLICATION_ID
    const val VERSION_NAME: String = BuildConfig.VERSION_NAME
    val VERSION_CODE: Int = BuildConfig.VERSION_CODE
    val IS_DEBUG: Boolean = BuildConfig.DEBUG

    const val WS_INITIAL_BACKOFF_MS = 1_500L
    const val WS_MAX_BACKOFF_MS = 30_000L
    const val WS_PING_INTERVAL_SECONDS = 20L
    const val TEXT_PREVIEW_MAX_BYTES = 200 * 1024L // 200 KB max text preview in RAM
}
