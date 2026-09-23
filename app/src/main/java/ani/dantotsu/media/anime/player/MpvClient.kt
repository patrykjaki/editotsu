package ani.dantotsu.media.anime.player

import android.content.Context
import android.view.Surface
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode

class MpvOperationException(
    val operation: String,
    val errorCode: Int? = null,
    cause: Throwable? = null
) : Exception("MPV operation '$operation' failed${errorCode?.let { " (rc=$it)" } ?: ""}${cause?.let { ": ${it.message}" } ?: ""}", cause)

interface MpvClientFactory {
    fun createFresh(): MpvClient
}

class RealMpvClientFactory : MpvClientFactory {
    override fun createFresh(): MpvClient = RealMpvClient(MPV())
}

fun redactOptionValue(name: String, value: String): String =
    MpvLogRedactor.redactOptionValue(name, value)

// CP4v1-04: the credential-bearing failure paths format their diagnostics ONLY through
// these builders, so tests can pin exactly what reaches logcat/shareLog on failure.
internal fun commandFailureDiagnostic(cause: Throwable): String =
    "RealMpvClient: mpv.command failed: ${MpvLogRedactor.redactDiagnosticText(cause.message)}"

internal fun setOptionFailureDiagnostic(name: String, safeValue: String, cause: Throwable): String =
    "RealMpvClient: setOptionString($name=$safeValue) failed: ${MpvLogRedactor.redactDiagnosticText(cause.message)}"

internal fun setPropertyFailureDiagnostic(name: String, value: String, cause: Throwable): String =
    "MPV setPropertyString($name=${MpvLogRedactor.redactPropertyValue(name, value)}) failed: " +
        MpvLogRedactor.redactDiagnosticText(cause.message)

interface MpvClient {
    fun create(context: Context?)
    fun init()
    fun destroy()
    fun attachSurface(surface: Surface)
    fun detachSurface()
    fun command(vararg args: String)
    fun setOptionString(name: String, value: String): Int
    fun setPropertyString(name: String, value: String)
    fun setPropertyBoolean(name: String, value: Boolean)
    fun setPropertyInt(name: String, value: Int)
    fun setPropertyDouble(name: String, value: Double)
    fun getPropertyInt(name: String): Int?
    fun getPropertyLong(name: String): Long?
    fun getPropertyDouble(name: String): Double?
    fun getPropertyBoolean(name: String): Boolean?
    fun getPropertyString(name: String): String?
    fun getPropertyNode(name: String): MPVNode?
    fun observeProperty(property: String, format: Int)
    fun addObserver(observer: MPV.EventObserver)
    fun removeObserver(observer: MPV.EventObserver)
    fun addLogObserver(observer: MPV.LogObserver)
    fun removeLogObserver(observer: MPV.LogObserver)
}

class RealMpvClient(private val mpv: MPV = MPV()) : MpvClient {

    override fun create(context: Context?) {
        try {
            nativeMarker("01 before nativeCreate")
            ani.dantotsu.util.Logger.log("RealMpvClient: calling mpv.create(context)")
            context?.let { mpv.create(it) }
            nativeMarker("02 after nativeCreate-return")
            ani.dantotsu.util.Logger.log("RealMpvClient: mpv.create completed successfully")
        } catch (t: Throwable) {
            nativeMarker("RealMpvClient: mpv.create failed: ${MpvLogRedactor.redactDiagnosticText(t.message)}")
            ani.dantotsu.util.Logger.log("RealMpvClient: mpv.create failed: ${MpvLogRedactor.redactDiagnosticText(t.message)}")
            throw MpvOperationException("create", cause = t)
        }
    }

    override fun init() {
        try {
            nativeMarker("03 before nativeInit")
            ani.dantotsu.util.Logger.log("RealMpvClient: calling mpv.init()")
            mpv.init()
            nativeMarker("04 after nativeInit-return")
            ani.dantotsu.util.Logger.log("RealMpvClient: mpv.init completed successfully")
        } catch (t: Throwable) {
            nativeMarker("RealMpvClient: mpv.init failed: ${MpvLogRedactor.redactDiagnosticText(t.message)}")
            ani.dantotsu.util.Logger.log("RealMpvClient: mpv.init failed: ${MpvLogRedactor.redactDiagnosticText(t.message)}")
            throw MpvOperationException("init", cause = t)
        }
    }

    override fun destroy() {
        try {
            nativeMarker("07 before nativeDestroy")
            ani.dantotsu.util.Logger.log("RealMpvClient: calling mpv.destroy()")
            mpv.destroy()
            nativeMarker("08 after nativeDestroy-return")
        } catch (t: Throwable) {
            nativeMarker("RealMpvClient: mpv.destroy error: ${MpvLogRedactor.redactDiagnosticText(t.message)}")
            ani.dantotsu.util.Logger.log("RealMpvClient: mpv.destroy error: ${MpvLogRedactor.redactDiagnosticText(t.message)}")
            throw MpvOperationException("destroy", cause = t)
        }
    }

    override fun attachSurface(surface: Surface) {
        try {
            nativeMarker("before attachSurface (isValid=${surface.isValid})")
            ani.dantotsu.util.Logger.log("RealMpvClient: calling mpv.attachSurface(isValid=${surface.isValid})")
            mpv.attachSurface(surface)
            nativeMarker("after attachSurface")
            ani.dantotsu.util.Logger.log("RealMpvClient: mpv.attachSurface completed successfully")
        } catch (t: Throwable) {
            nativeMarker("RealMpvClient: mpv.attachSurface failed: ${MpvLogRedactor.redactDiagnosticText(t.message)}")
            ani.dantotsu.util.Logger.log("RealMpvClient: mpv.attachSurface failed: ${MpvLogRedactor.redactDiagnosticText(t.message)}")
            throw MpvOperationException("attachSurface", cause = t)
        }
    }

    override fun detachSurface() {
        try {
            nativeMarker("before detachSurface")
            ani.dantotsu.util.Logger.log("RealMpvClient: calling mpv.detachSurface()")
            mpv.detachSurface()
            nativeMarker("after detachSurface")
        } catch (t: Throwable) {
            nativeMarker("RealMpvClient: mpv.detachSurface error: ${MpvLogRedactor.redactDiagnosticText(t.message)}")
            ani.dantotsu.util.Logger.log("RealMpvClient: mpv.detachSurface error: ${MpvLogRedactor.redactDiagnosticText(t.message)}")
            throw MpvOperationException("detachSurface", cause = t)
        }
    }

    override fun command(vararg args: String) {
        try {
ani.dantotsu.util.Logger.log(
                "RealMpvClient: calling mpv.command(" +
                    MpvLogRedactor.redactCommandArgs(args.toList()).joinToString(", ") + ")"
            )
            mpv.command(*args)
        } catch (t: Throwable) {
            val diag = commandFailureDiagnostic(t)
            nativeMarker(diag)
            ani.dantotsu.util.Logger.log(diag)
            throw MpvOperationException("command(${args.firstOrNull() ?: ""})", cause = t)
        }
    }

    override fun setOptionString(name: String, value: String): Int {
        val safeValue = redactOptionValue(name, value)
        return try {
            nativeMarker("before setOptionString $name=$safeValue")
            ani.dantotsu.util.Logger.log("RealMpvClient: setOptionString($name=$safeValue)")
            val res = mpv.setOptionString(name, value)
            nativeMarker("after setOptionString $name rc=$res")
            ani.dantotsu.util.Logger.log("RealMpvClient: setOptionString($name) returned $res")
            res
        } catch (t: Throwable) {
            val diag = setOptionFailureDiagnostic(name, safeValue, t)
            nativeMarker(diag)
            ani.dantotsu.util.Logger.log(diag)
            throw MpvOperationException("setOptionString($name)", cause = t)
        }
    }

    override fun setPropertyString(name: String, value: String) {
        try {
            mpv.setPropertyString(name, value)
        } catch (e: Exception) {
            ani.dantotsu.util.Logger.log(setPropertyFailureDiagnostic(name, value, e))
            throw MpvOperationException("setPropertyString($name)", cause = e)
        }
    }

    override fun setPropertyBoolean(name: String, value: Boolean) {
        try {
            mpv.setPropertyBoolean(name, value)
        } catch (e: Exception) {
            ani.dantotsu.util.Logger.log("MPV setPropertyBoolean($name, $value) failed: ${MpvLogRedactor.redactDiagnosticText(e.message)}")
            throw MpvOperationException("setPropertyBoolean($name)", cause = e)
        }
    }

    override fun setPropertyInt(name: String, value: Int) {
        try {
            mpv.setPropertyInt(name, value)
        } catch (e: Exception) {
            ani.dantotsu.util.Logger.log("MPV setPropertyInt($name, $value) failed: ${MpvLogRedactor.redactDiagnosticText(e.message)}")
            throw MpvOperationException("setPropertyInt($name)", cause = e)
        }
    }

    override fun setPropertyDouble(name: String, value: Double) {
        try {
            mpv.setPropertyDouble(name, value)
        } catch (e: Exception) {
            ani.dantotsu.util.Logger.log("MPV setPropertyDouble($name, $value) failed: ${MpvLogRedactor.redactDiagnosticText(e.message)}")
            throw MpvOperationException("setPropertyDouble($name)", cause = e)
        }
    }

    override fun getPropertyInt(name: String): Int? {
        return try {
            mpv.getPropertyInt(name)
        } catch (_: Exception) {
            null
        }
    }

    override fun getPropertyLong(name: String): Long? {
        return try {
            mpv.getPropertyLong(name)
        } catch (_: Exception) {
            null
        }
    }

    override fun getPropertyDouble(name: String): Double? {
        return try {
            mpv.getPropertyDouble(name)
        } catch (_: Exception) {
            null
        }
    }

    override fun getPropertyBoolean(name: String): Boolean? {
        return try {
            mpv.getPropertyBoolean(name)
        } catch (_: Exception) {
            null
        }
    }

    override fun getPropertyString(name: String): String? {
        return try {
            mpv.getPropertyString(name)
        } catch (_: Exception) {
            null
        }
    }

    override fun getPropertyNode(name: String): MPVNode? {
        return try {
            mpv.getPropertyNode(name)
        } catch (_: Exception) {
            null
        }
    }

    override fun observeProperty(property: String, format: Int) {
        try {
            mpv.observeProperty(property, format)
        } catch (e: Exception) {
            ani.dantotsu.util.Logger.log("MPV observeProperty($property) failed: ${MpvLogRedactor.redactDiagnosticText(e.message)}")
            throw MpvOperationException("observeProperty($property)", cause = e)
        }
    }

    override fun addObserver(observer: MPV.EventObserver) {
        try {
            mpv.addObserver(observer)
        } catch (e: Exception) {
            throw MpvOperationException("addObserver", cause = e)
        }
    }

    override fun removeObserver(observer: MPV.EventObserver) {
        try {
            mpv.removeObserver(observer)
        } catch (_: Exception) {}
    }

    override fun addLogObserver(observer: MPV.LogObserver) {
        try {
            mpv.addLogObserver(observer)
        } catch (_: Exception) {}
    }

    override fun removeLogObserver(observer: MPV.LogObserver) {
        try {
            mpv.removeLogObserver(observer)
        } catch (_: Exception) {}
    }
}

