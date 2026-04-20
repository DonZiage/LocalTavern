package chat.donzi.localtavern

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform