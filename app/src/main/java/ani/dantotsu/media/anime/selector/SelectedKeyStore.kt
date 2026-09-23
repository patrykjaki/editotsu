package ani.dantotsu.media.anime.selector

import ani.dantotsu.settings.saving.PrefManager

/**
 * Persistence-owned exact / family storage on top of the existing
 * custom-pref API. NO new field is added to `Selected`, `VideoServer`,
 * or `Episode`. `PrefName.MakeDefault` is the existing Boolean.
 */
object SelectedKeyStore {
    fun key(mediaId: Int): String = "SelectedKey-$mediaId"

    fun load(mediaId: Int): String? {
        return PrefManager.getNullableCustomVal(key(mediaId), null, String::class.java)
    }

    fun save(mediaId: Int, value: String?) {
        if (value == null) {
            PrefManager.setCustomVal(key(mediaId), null as String?)
        } else {
            PrefManager.setCustomVal(key(mediaId), value)
        }
    }
}

object SelectedFamilyStore {
    fun key(mediaId: Int): String = "SelectedFamily-v1-$mediaId"

    fun load(mediaId: Int): FamilyPayload? {
        val raw = PrefManager.getNullableCustomVal(key(mediaId), null, String::class.java)
        return SelectedFamilyCodec.decode(raw)
    }

    fun save(mediaId: Int, payload: FamilyPayload?): Boolean {
        if (payload == null) {
            PrefManager.setCustomVal(key(mediaId), null as String?)
            return true
        }
        val encoded = SelectedFamilyCodec.encode(payload) ?: return false
        PrefManager.setCustomVal(key(mediaId), encoded)
        return true
    }
}
