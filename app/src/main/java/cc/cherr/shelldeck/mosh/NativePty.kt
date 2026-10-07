package cc.cherr.shelldeck.mosh

/** Loaded only for Mosh: plain SSH never needs a native process. */
internal object NativePty {
    init { System.loadLibrary("shelldeck-pty") }
    external fun spawn(args: Array<String>, environment: Array<String>, rows: Int, cols: Int): IntArray
    external fun resize(fd: Int, rows: Int, cols: Int)
    external fun awaitExit(pid: Int)
    external fun reap(pid: Int): Int
    external fun signal(pid: Int, signal: Int)
}
