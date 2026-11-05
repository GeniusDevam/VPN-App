# Release signing (finding B1)

## What happened
`tools/release.jks` and its passwords were committed to this public repository, and CI used them. **That signing identity is
compromised and must never be trusted again.** Deleting the file in a newer commit does not help: it stays in git history and
in every clone. Anyone can build an APK that Android treats as a legitimate update to an install signed with it.

## What changed in the repository
* The keystore is removed from the tree; `.gitignore` blocks `*.jks`, `*.keystore`, `*.p12`, `*.pem`.
* CI signs **release builds only with a key from encrypted GitHub secrets** (`RELEASE_KEYSTORE_B64`, `RELEASE_KEYSTORE_PASSWORD`,
  `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`). Only the real `arm64` job on this repository ever receives them, they are passed to
  Gradle through environment variables (never on a command line), masked in logs, and the file is deleted at the end of the job.
* Everything else (the x86_64 emulator build, builds from forks) is signed with a **throw-away key generated inside the job**. Those
  APKs prove nothing about origin and are never published.
* **If the secrets are not configured, CI publishes nothing.** The app still builds and is tested. This is deliberate: continuing to
  publish APKs under the leaked key would keep the supply-chain hole open. (This changes the previous behaviour; see "To do" below.)
* Releases are now **immutable and versioned** (`v1.0.<build>`), each with `VPN-App.apk.sha256`, the signing-certificate SHA-256 in
  the notes, and a GitHub **build-provenance attestation** (`gh attestation verify VPN-App.apk -R GeniusDevam/VPN-App`).
  `releases/latest/download/VPN-App.apk` keeps working because the newest release is marked latest. The old mutable `latest`
  release (which carried a leaked-key APK) is deleted after the first new release succeeds.
* If the repository variable `RELEASE_CERT_SHA256` is set, a build whose certificate differs **fails**.

## To do (needs you; I cannot and should not create or hold your key)
0. **Delete the public `latest` release (build #17) now.** It is still downloadable, it carries an APK signed with the leaked key, and the README's install link points at it. The pipeline only removes it after a new signed release exists, and nothing available to the authoring work can delete a release. Do it by hand (GitHub > Releases > `latest` > Delete) rather than waiting.
1. On your own machine run `tools/generate-release-key.sh`. It creates a 4096-bit RSA key outside the repo, keeps the password in a
   0600 file, and prints the **public** certificate fingerprint plus the exact `gh secret set` commands.
2. Back the key up offline. Losing it means you can never ship an update Android will accept for installs signed with it.
3. Publish the fingerprint (README, release notes). Users compare it before installing.
4. Optional hardening: protect the default branch and make the secrets available only to a protected **environment** that requires
   approval, so a compromised contributor token cannot sign a release.

## Migrating existing installs
Android binds an app's identity to its signing certificate. A different key cannot update an install signed with the old one
(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), **except** through APK Signature Scheme v3 key rotation (`apksigner rotate`), which needs the
old key to vouch for the new one.

* **Recommended: uninstall and reinstall once** from a release signed with the new key. Cost: local app data is lost (settings and
  history; all of it is encrypted with a Keystore key that dies with the app anyway). Benefit: the install stops trusting the old key.
* **Rotation (Android 9+ only; the app supports Android 8, where it is impossible)** keeps data, but it cannot undo the exposure:
  because the old key is public, *anyone* can also create a rotation lineage from it to a key of their own. An install is only
  safe once it has actually updated to your new key. Until then it will accept an attacker's APK signed with the old key. If you
  choose rotation, do it quickly, do not let the old key stay trusted any longer than needed, and tell users to reinstall if unsure.
* The old certificate fingerprint is in the git history (`git log --stat -- tools/release.jks`). Treat any APK carrying it as untrusted.
* This only matters to people who installed a build. If you are the only user, reinstalling takes a minute.

## Limits (be honest about them)
Provenance attestations and checksums prove *which workflow built a file*, not that the code is safe. The signing key is the root
of trust for updates; protect it as such. Git history still contains the old keystore; rewriting history of a public repo breaks
every clone and does not un-leak anything, so it is not recommended.
