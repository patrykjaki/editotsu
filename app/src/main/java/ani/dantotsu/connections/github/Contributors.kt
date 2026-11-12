package ani.dantotsu.connections.github

import ani.dantotsu.settings.Developer

/**
 * The people behind the app. A fixed list: Editotsu's maintainer, then the authors of
 * Dantotsu, the app Editotsu is built on. No network request, so the list cannot fail
 * to load.
 */
class Contributors {

    fun getContributors(): Array<Developer> = arrayOf(
        github("patrykjaki", "patrykjaki", "Editotsu maintainer"),
        github("rebelonion", "rebelonion", "Dantotsu: author and maintainer"),
        github("aayush262", "aayush2622", "Dantotsu: developer"),
        github("sneazy-ibo", "sneazy-ibo", "Dantotsu: contributor"),
        github("itsmechinmoy", "itsmechinmoy", "Dantotsu: contributor"),
        github("Sadwhy", "Sadwhy", "Dantotsu: contributor"),
        github("TwistedUmbrellaX", "AbandonedCart", "Dantotsu: contributor"),
        github("WaiWhat", "WaiWhat", "Dantotsu: icon designer"),
        Developer(
            "Everyone else who built Dantotsu",
            "https://github.com/rebelonion.png",
            "Full contributor list",
            "https://github.com/rebelonion/Dantotsu/graphs/contributors",
        ),
    )

    private fun github(name: String, login: String, role: String) =
        Developer(name, "https://github.com/$login.png", role, "https://github.com/$login")
}
