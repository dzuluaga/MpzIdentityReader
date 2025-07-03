package org.multipaz.identityreader

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform