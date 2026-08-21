package ani.dantotsu.media.anime.player

object MpvTrackMapper {

    @Suppress("UNCHECKED_CAST")
    fun parseTrackList(rawList: List<Map<String, Any?>>): List<PlayerTrack> {
        val result = mutableListOf<PlayerTrack>()

        for (item in rawList) {
            val id = (item["id"] as? Number)?.toInt() ?: continue
            val typeStr = (item["type"] as? String)?.lowercase() ?: continue

            val type = when (typeStr) {
                "video" -> TrackType.VIDEO
                "audio" -> TrackType.AUDIO
                "sub" -> TrackType.SUBTITLE
                else -> continue
            }

            val title = item["title"] as? String
            val lang = item["lang"] as? String
            val codec = item["codec"] as? String
            val selected = (item["selected"] as? Boolean) == true
            val external = (item["external"] as? Boolean) == true
            val default = (item["default"] as? Boolean) == true
            val forced = (item["forced"] as? Boolean) == true

            result.add(
                PlayerTrack(
                    id = id,
                    type = type,
                    name = title ?: lang ?: "Track #$id",
                    language = lang,
                    codec = codec,
                    selected = selected,
                    external = external,
                    default = default,
                    forced = forced
                )
            )
        }

        return result
    }
}
