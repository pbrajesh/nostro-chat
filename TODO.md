# To-do

## Key backup and restore (moving to a new phone)

Today the private key is locked to the phone's Keystore, so a new phone or a reinstall
means a new identity.

- [ ] **Export:** in Settings, show the private key as a NIP-49 `ncryptsec` (scrypt +
      XChaCha20-Poly1305, encrypted with a backup password the user chooses, separate
      from the 4-character PIN), as a QR code and as copyable text. Ask for the PIN first.
- [ ] **Import at first setup:** next to "Create my keys", add "Restore from backup":
      scan or paste the `ncryptsec`, enter the backup password, then choose a PIN for this
      phone. The key is then stored the same way as a new one (scrypt(PIN) + AES-GCM +
      Keystore).
- [ ] Tests against the NIP-49 test vectors in `core/`.
- [ ] Contacts and settings are not part of the key; decide whether to include them in
      the backup or let contacts come back from relays.

## Play Store release

- [ ] **Signed release build:** a GitHub Actions workflow that builds a release `.aab`,
      signed with an upload key kept in GitHub Actions secrets (not committed like the
      debug key), and enrolls the app in Play App Signing.
- [ ] **Privacy policy:** a hosted page explaining that there is no server of our own, that
      keys and messages stay on the phone (encrypted), and that encrypted messages pass
      through public Nostr relays. Required by Play for apps with camera access and messaging.
- [ ] **Store listing:** app name, short and full description, icon (512×512), feature
      graphic, phone screenshots, content rating questionnaire, and the Data safety form.
- [ ] A real app icon instead of the system chat icon.
