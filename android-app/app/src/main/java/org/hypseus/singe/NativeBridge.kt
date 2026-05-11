package org.hypseus.singe

object NativeBridge {
    init {
        System.loadLibrary("hypseus")
    }

    @JvmStatic
    external fun nativeRun(args: Array<String>, homeDir: String, dataDir: String): Int
}
