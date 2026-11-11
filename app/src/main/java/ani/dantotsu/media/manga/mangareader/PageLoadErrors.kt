package ani.dantotsu.media.manga.mangareader

/** The OutOfMemoryError in [e] or its causes (Glide wraps it in an ExecutionException). */
fun findOutOfMemory(e: Throwable): OutOfMemoryError? =
    generateSequence(e) { it.cause.takeIf { cause -> cause !== it } }
        .take(16)
        .filterIsInstance<OutOfMemoryError>()
        .firstOrNull()
