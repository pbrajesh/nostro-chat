package app.mobilechat.core

import java.security.MessageDigest

/**
 * Friendly names like `roaring_lion`, derived from the public key so that anyone
 * who has your npub sees the same name you do, without asking a relay.
 * Names can collide; the npub is the real identity.
 */
object Usernames {
    val adjectives = listOf(
        "roaring", "brave", "calm", "clever", "cosmic", "curious", "daring", "dizzy",
        "eager", "fancy", "fearless", "fierce", "fluffy", "gentle", "giant", "glowing",
        "golden", "grumpy", "happy", "hidden", "humble", "jolly", "jumpy", "kind",
        "lazy", "lively", "lucky", "mighty", "misty", "noble", "nimble", "proud",
        "quick", "quiet", "rapid", "rusty", "sleepy", "sly", "sneaky", "silent",
        "silly", "sparkly", "speedy", "spotted", "stormy", "striped", "sunny", "swift",
        "tiny", "wild", "wise", "witty", "zesty", "bold", "breezy", "cheeky",
        "dapper", "frosty", "howling", "mellow", "plucky", "snowy", "wandering", "zany",
    )

    val animals = listOf(
        "lion", "tiger", "panda", "otter", "falcon", "wolf", "fox", "bear",
        "eagle", "shark", "dolphin", "koala", "lynx", "moose", "owl", "penguin",
        "rabbit", "raven", "seal", "sloth", "swan", "turtle", "whale", "zebra",
        "badger", "beaver", "bison", "camel", "cheetah", "cobra", "crane", "deer",
        "elephant", "ferret", "gecko", "giraffe", "gorilla", "hawk", "hedgehog", "heron",
        "hippo", "jaguar", "kangaroo", "lemur", "leopard", "llama", "lobster", "mongoose",
        "narwhal", "octopus", "parrot", "pelican", "puma", "rhino", "salmon", "squirrel",
        "stork", "toucan", "walrus", "weasel", "yak", "panther", "orca", "coyote",
    )

    fun forPublicKey(publicKey: ByteArray): String {
        val h = MessageDigest.getInstance("SHA-256").digest(publicKey)
        val a = ((h[0].toInt() and 0xff) shl 8 or (h[1].toInt() and 0xff)) % adjectives.size
        val b = ((h[2].toInt() and 0xff) shl 8 or (h[3].toInt() and 0xff)) % animals.size
        return "${adjectives[a]}_${animals[b]}"
    }
}
