package com.hartmann.crosspromo.internal

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private val mainHandler = Handler(Looper.getMainLooper())

fun launchInIo(block: suspend () -> Unit) {
    ioScope.launch { runCatching { block() } }
}

fun onMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post { runCatching { block() } }
}
