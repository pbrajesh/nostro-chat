# Architecture

Nostr Chat is a single Android app with no backend of its own. Everything private stays
on the phone, and messages travel through public Nostr relays as encrypted gift wraps.

```mermaid
flowchart TB
    subgraph phone["Android phone"]
        subgraph ui["UI (Jetpack Compose)"]
            pin["PinScreen<br/>set up / unlock"]
            home["HomeScreen<br/>Chats · Profile · Settings"]
            chat["ChatScreen"]
            qr["QR code + scanner<br/>(ZXing)"]
        end

        vm["AppViewModel<br/>screen state, lock / unlock"]

        subgraph data["data (Android)"]
            vault["KeyVault"]
            ks["KeystoreWrapper<br/>AES-256-GCM key in<br/>Android Keystore"]
            messenger["Messenger<br/>send / receive, retention"]
            store["MessageStore<br/>SQLCipher DB"]
            contacts["ContactStore"]
            settings["Settings<br/>auto-delete"]
            reldir["RelayDirectory<br/>discovered relays,<br/>contacts' inbox relays"]
        end

        subgraph core["core (plain Kotlin, unit tested on the JVM)"]
            pincrypto["PinCrypto<br/>scrypt + AES-GCM"]
            keys["NostrKeys · Bech32<br/>secp256k1, npub"]
            names["Usernames<br/>adjective_animal"]
            nip17["Nip17 · Nip44 · Event<br/>gift wrap, encryption, signing"]
            pool["RelayPool<br/>WebSockets, NIP-42 auth"]
            nip66["Nip66 · RelayLists<br/>relay discovery"]
            dbkey["LocalKeys<br/>DB key from private key"]
        end

        prefs[("SharedPreferences<br/>sealed key, public key,<br/>contacts, settings")]
        dbfile[("messages.db<br/>encrypted")]
    end

    relays(["Public Nostr relays<br/>inbox: damus · nos.lol · primal · 0xchat<br/>+ fastest from NIP-66 monitors<br/>+ each contact's inbox relays"])
    others(["Other Nostr apps<br/>0xchat, Amethyst, …"])

    pin --> vm
    home --> vm
    chat --> vm
    home --- qr
    vm --> vault
    vm --> messenger
    vm --> contacts
    vm --> settings
    vault --> pincrypto
    vault --> ks
    vault --> keys
    vault --> prefs
    contacts --> prefs
    settings --> prefs
    vm -. "unlocked private key" .-> dbkey
    dbkey --> store
    messenger --> store
    store --> dbfile
    messenger --> nip17
    messenger --> pool
    messenger --> nip66
    messenger --> reldir
    reldir --> prefs
    home --> names
    pool <== "encrypted gift wraps (kind 1059)" ==> relays
    relays <==> others
```

Messages only reach the screens through the database: `Messenger` writes every message it
receives or sends to `MessageStore`, then reloads the conversations from it.

## How the private key is protected

```mermaid
flowchart LR
    pinin["PIN<br/>(4 letters/digits)"] --> scrypt["scrypt<br/>N=2^15, r=8"] --> aes1["AES-256-GCM"]
    sk["Nostr private key"] --> aes1 --> aes2["AES-256-GCM<br/>Android Keystore key"] --> sp[("SharedPreferences")]
    sk -. "HMAC-SHA256" .-> dbk["Database key"] --> sqlc[("SQLCipher messages.db")]
```

The private key exists only in memory after a correct PIN. The message database key is
derived from it, so the database can't be opened without the PIN either. Five wrong PINs
start a lockout that doubles each time, up to an hour.

The Keystore key can't be exported, so a copy of these files is useless on another
phone. To move phones, the app writes a backup file instead (`core/Backup.kt`):
`"NCBK" | version | scrypt params | salt | IV | AES-256-GCM(gzip(JSON))`, keyed with
scrypt (N=2^16, r=8) of a backup password. Restoring re-wraps the key under the new
phone's PIN and Keystore and re-inserts the messages into a fresh `messages.db`.

## How a message travels (NIP-17)

```mermaid
sequenceDiagram
    participant A as Alice's app
    participant R as Relays
    participant B as Bob's app
    A->>A: rumor (kind 14, unsigned)<br/>seal (kind 13) = NIP-44 encrypt + Alice signs<br/>gift wrap (kind 1059) = NIP-44 encrypt with a one-time key
    A->>R: EVENT gift wrap for Bob, and a copy for Alice
    B->>R: REQ kinds [1059], #p = Bob
    R-->>B: EVENT gift wrap
    B->>B: unwrap, check seal signature,<br/>save to messages.db
```

Relays only see a one-time key, the recipient's public key and a randomized timestamp.
They can't tell who sent a message or read what it says.

## Modules

| Module | What lives there |
| --- | --- |
| `core/` (`com.pbrajesh.mobile.nostrchat.core`) | Pure Kotlin: keys, bech32, PIN encryption, NIP-44, NIP-17, relay pool, retention. Tested with `./gradlew :core:test`. |
| `app/` (`com.pbrajesh.mobile.nostrchat`) | Android: Keystore, storage (SharedPreferences, SQLCipher), ViewModel, Compose screens, QR. |
| `.github/workflows/android.yml` | Runs the tests, sends a real message over public relays, and builds the debug APK. |
