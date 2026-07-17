package com.aynthor.taskswap.task

/**
 * Dual-screen shells / launchers that must never participate in swap or push.
 * Cocoon is often not the default HOME app, so PackageManager launcher detection misses it.
 */
object ShellPackages {
    val ALWAYS_IGNORED: Set<String> = setOf(
        "rip.moth.cocoonshell",
        "com.android.systemui",
        "com.odin.gameassistant",
        "com.odin.dualscreen.assistant"
    )

    fun isShellOrIgnored(packageName: String, extraIgnored: Set<String> = emptySet()): Boolean =
        packageName in ALWAYS_IGNORED || packageName in extraIgnored
}
