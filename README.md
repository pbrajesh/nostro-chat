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
- **Chat:** tap a contact to open a conversation. Messages are NIP-17 private direct
  messages (NIP-44 encryption inside NIP-59 gift wraps), sent through public relays
  (`relay.damus.io`, `nos.lol`, `relay.primal.net`, `relay.0xchat.com`). They work with
  other NIP-17 apps such as 0xchat and Amethyst. People who message you show up in Chats
  even before you add them.
- **Discoverability:** on unlock the app publishes your username (kind 0) and the relays
  you read messages from (kind 10050).
- **Relays:** the four relays above are your inbox and the built-in fallback. Once a day
  the app also reads NIP-66 relay monitor reports and connects to the six fastest free,
  open relays it finds (the choice is remembered between launches). Before sending, it
  looks up the recipient's own inbox relays (their kind 10050) and delivers there too.
- **Saved messages:** every message that arrives from a relay, and every message you send,
  is written to a SQLCipher (AES-256) database on the phone, and the screens read
  messages only from that database. Its key is derived from your private key, which only
  the PIN unlocks, so the database can't be read without the PIN.
- **Auto-delete:** Settings → "Delete messages after" 1 day, 10 days, or never (default).
  Expired messages are removed when you unlock and every 10 minutes while the app is open,
  and ignored if a relay sends them again.
- **Moving to a new phone:** Settings → "Back up to a file" asks for your PIN and a
  backup password (8+ characters, separate from the PIN), then saves one `.ncbak` file
  with your private key, messages, contacts and the auto-delete setting. The file is
  gzip'd JSON encrypted with AES-256-GCM under scrypt(password) (N=2^16, r=8, p=1), so it
  is not tied to the old phone's Keystore. Copy it to the new phone any way you like, tap
  "Restore from a backup file" at first setup, enter the backup password and choose a PIN
  for the new phone. Copying the app's own files doesn't work, because the Keystore key
  that wraps your private key can't leave the old phone.

## Nostr standards (NIPs) used

| NIP | What the app uses it for |
| --- | --- |
| [NIP-01](https://github.com/nostr-protocol/nips/blob/master/01.md) | Events, ids, Schnorr signatures, the relay protocol (`REQ`, `EVENT`, `EOSE`, `OK`, `CLOSE`), and the kind 0 profile with your username |
| [NIP-17](https://github.com/nostr-protocol/nips/blob/master/17.md) | Private direct messages (kind 14) and inbox relay lists (kind 10050) |
| [NIP-19](https://github.com/nostr-protocol/nips/blob/master/19.md) | `npub` encoding of public keys |
| [NIP-21](https://github.com/nostr-protocol/nips/blob/master/21.md) | `nostr:` URIs in the QR code |
| [NIP-42](https://github.com/nostr-protocol/nips/blob/master/42.md) | Answering relay `AUTH` challenges, so relays that guard gift wraps will hand them over |
| [NIP-44](https://github.com/nostr-protocol/nips/blob/master/44.md) | Version 2 encryption of seals and gift wraps (checked against the official test vectors) |
| [NIP-59](https://github.com/nostr-protocol/nips/blob/master/59.md) | Seals (kind 13) and gift wraps (kind 1059) that hide the sender and timing |
| [NIP-66](https://github.com/nostr-protocol/nips/blob/master/66.md) | Relay discovery from monitor reports (kind 30166) |

### About the PIN

A 4-character PIN has about 14.8 million combinations, which a determined attacker can
brute-force if they get the encrypted data off the device. The Keystore layer is what
stops that: the guesses have to run on your phone, through the app. Treat the PIN as a
screen lock, not as strong encryption. Uninstalling the app or clearing its data deletes
your identity unless you have a backup file. A backup file has no Keystore layer, so its
strength is the backup password's: anyone with the file can guess passwords offline.

## Getting the APK without Android Studio

Every push runs the **Build APK** GitHub Actions workflow. Open the repository's
**Actions** tab, pick the latest run, and download `nostr-chat-debug-apk` under
**Artifacts**. Unzip it and open the `.apk` on your phone (you'll need to allow installs
from that app). The debug signing key is committed in `app/debug.keystore` so each new
build installs over the previous one without wiping your keys.

## To-do

Planned work, including the Play Store release, is in [TODO.md](TODO.md).

## Layout

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for diagrams.

- `core/` — plain Kotlin: bech32/npub, key generation, PIN encryption, usernames,
  NIP-44, NIP-17 gift wraps, relay connections. Unit tests run on the JVM:
  `./gradlew :core:test` (NIP-44 is checked against the official test vectors). CI also
  sends a real message between two throwaway keys over the public relays.
- `app/` — the Android app (`com.pbrajesh.mobile.nostrchat`): Keystore wrapper, storage, Compose screens, QR scanning
  (ZXing).
