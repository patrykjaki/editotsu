package ani.dantotsu.media.anime.player

import android.content.Context
import android.view.Surface
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode

class FakeMpvClientFactory(private val clientProvider: () -> MpvClient = { FakeMpvClient() }) : MpvClientFactory {
    override fun createFresh(): MpvClient = clientProvider()
}

class FakeMpvClient : MpvClient {

    val executedCommands = mutableListOf<List<String>>()
    val stringProperties = mutableMapOf<String, String>()
    val booleanProperties = mutableMapOf<String, Boolean>()
    val intProperties = mutableMapOf<String, Int>()
    val doubleProperties = mutableMapOf<String, Double>()
    val nodeProperties = mutableMapOf<String, MPVNode>()
    val observedProperties = mutableMapOf<String, Int>()
    val observers = mutableListOf<MPV.EventObserver>()
    val logObservers = mutableListOf<MPV.LogObserver>()

    var isCreated = false
    var isInitialized = false
    var isDestroyed = false
    var attachedSurface: Surface? = null
    var optionApplyErrorRc: Int? = null

    override fun create(context: Context?) {
        isCreated = true
    }

    override fun init() {
        isInitialized = true
    }

    override fun destroy() {
        isDestroyed = true
        isInitialized = false
    }

    override fun attachSurface(surface: Surface) {
        attachedSurface = surface
    }

    override fun detachSurface() {
        attachedSurface = null
    }

    override fun command(vararg args: String) {
        executedCommands.add(args.toList())
    }

    override fun setOptionString(name: String, value: String): Int {
        stringProperties[name] = value
        return optionApplyErrorRc ?: 0
    }

    override fun setPropertyString(name: String, value: String) {
        stringProperties[name] = value
    }

    override fun setPropertyBoolean(name: String, value: Boolean) {
        booleanProperties[name] = value
    }

    override fun setPropertyInt(name: String, value: Int) {
        intProperties[name] = value
    }

    override fun setPropertyDouble(name: String, value: Double) {
        doubleProperties[name] = value
    }

    override fun getPropertyInt(name: String): Int? = intProperties[name]

    override fun getPropertyLong(name: String): Long? = intProperties[name]?.toLong()

    override fun getPropertyDouble(name: String): Double? = doubleProperties[name]

    override fun getPropertyBoolean(name: String): Boolean? = booleanProperties[name]

    override fun getPropertyString(name: String): String? = stringProperties[name]

    override fun getPropertyNode(name: String): MPVNode? = nodeProperties[name]

    override fun observeProperty(property: String, format: Int) {
        observedProperties[property] = format
    }

    override fun addObserver(observer: MPV.EventObserver) {
        observers.add(observer)
    }

    override fun removeObserver(observer: MPV.EventObserver) {
        observers.remove(observer)
    }

    override fun addLogObserver(observer: MPV.LogObserver) {
        logObservers.add(observer)
    }

    override fun removeLogObserver(observer: MPV.LogObserver) {
        logObservers.remove(observer)
    }

    // Helper functions for test simulation
    fun emitEvent(eventId: Int, playlistEntryId: Long? = null, reason: String? = null, fileError: String? = null) {
        observers.forEach { observer ->
            if (observer is MpvPlaybackEngine) {
                observer.handleEngineEvent(eventId, playlistEntryId, reason, fileError)
            }
        }
    }

    fun emitProperty(property: String, value: Double) {
        doubleProperties[property] = value
        observers.forEach { it.eventProperty(property, value) }
    }

    fun emitProperty(property: String, value: Boolean) {
        booleanProperties[property] = value
        observers.forEach { it.eventProperty(property, value) }
    }

    fun emitProperty(property: String, value: String) {
        stringProperties[property] = value
        observers.forEach { it.eventProperty(property, value) }
    }

    fun emitProperty(property: String, value: Long) {
        intProperties[property] = value.toInt()
        observers.forEach { it.eventProperty(property, value) }
    }
}
