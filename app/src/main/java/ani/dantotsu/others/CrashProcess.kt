package ani.dantotsu.others

/** The `:error_process` that shows [CrashActivity]. */
object CrashProcess {
    const val SUFFIX = ":error_process"

    fun isCrashProcess(processName: String?): Boolean = processName?.endsWith(SUFFIX) == true
}
