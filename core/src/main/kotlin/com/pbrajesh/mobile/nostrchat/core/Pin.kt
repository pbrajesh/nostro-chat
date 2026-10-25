package com.pbrajesh.mobile.nostrchat.core

/** The app PIN: exactly four letters or digits, case-sensitive. */
object Pin {
    const val LENGTH = 4

    fun isValid(pin: CharSequence): Boolean =
        pin.length == LENGTH && pin.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
}
