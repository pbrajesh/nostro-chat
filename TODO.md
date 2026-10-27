# To-do

## Key backup and restore (moving to a new phone)

- [x] **Full backup file:** Settings → "Back up to a file" saves the key, messages,
      contacts and settings, encrypted with a backup password; "Restore from a backup
      file" at first setup brings them back on the new phone.
- [ ] **Key-only export:** show the private key as a NIP-49 `ncryptsec` (QR and text) so
      it can be moved into other Nostr apps, and accept one at restore.

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
