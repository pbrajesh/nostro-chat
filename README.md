# Nostr Chat (Android)

A small Android Nostr client in Kotlin and Jetpack Compose. No backend: identity is a
secp256k1 key pair that lives on the phone.

## What it does so far

- **First launch:** asks for a 4-character PIN (letters or digits, case-sensitive), then
  generates a Nostr key pair.
- **Key protection:** the private key is encrypted with AES-256-GCM under a key derived
  from the PIN with scrypt (N=2^15, r=8, p=1), and that result is encrypted again with an
  AES key held in the Android Keystore. After 5 wrong PINs the app makes you wait
  (30 seconds, doubling, up to an hour).
- **Username:** a friendly `adjective_animal` name (e.g. `roaring_lion`) derived from the
  public key, so anyone who has your npub sees the same name.
- **Profile:** your npub as text and as a QR code (`nostr:npub1…`, NIP-21), with copy and
  share buttons.
- **Contacts:** add by scanning a QR code or pasting an npub (or 64-char hex).

Messaging over relays is not in this version yet.

### About the PIN

A 4-character PIN has about 14.8 million combinations, which a determined attacker can
brute-force if they get the encrypted data off the device. The Keystore layer is what
stops that: the guesses have to run on your phone, through the app. Treat the PIN as a
screen lock, not as strong encryption. There is no key backup yet, so uninstalling the app
or clearing its data deletes your identity.

## Getting the APK without Android Studio

Every push runs the **Build APK** GitHub Actions workflow. Open the repository's
**Actions** tab, pick the latest run, and download `nostr-chat-debug-apk` under
**Artifacts**. Unzip it and open the `.apk` on your phone (you'll need to allow installs
from that app). The debug signing key is committed in `app/debug.keystore` so each new
build installs over the previous one without wiping your keys.

## Layout

- `core/` — plain Kotlin: bech32/npub, key generation, PIN encryption, usernames. Unit
  tests run on the JVM: `./gradlew :core:test`.
- `app/` — the Android app: Keystore wrapper, storage, Compose screens, QR scanning
  (ZXing).
