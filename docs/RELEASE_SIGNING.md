# Release signing (finding B1)

## Current status (25 November 2026)
* **Done.** The permanent release key exists (RSA 4096, `CN=VPN App release, O=GeniusDevam`, valid until 2051) and lives only in the
  protected environment `release` (four secrets, plus the public variable `RELEASE_CERT_SHA256`). The GitHub settings below are in
  place, the old leaked-key release (build #17) is deleted, and the default branch is `main`.
* **First release: [v1.0.0](https://github.com/GeniusDevam/VPN-App/releases/tag/v1.0.0)**, tag on commit `f95296a`, CI run
  [#48](https://github.com/GeniusDevam/VPN-App/actions/runs/45664646878), deployment approved manually by the key holder.
* **Certificate SHA-256 `0838262d4e4d35da8527a5555c90552221b07db3804d17966e273f2fe1e5998a`**, the same in all of: the pinned
  environment variable (checked by the `sign` job, which logged "matches the pinned RELEASE_CERT_SHA256"), the release notes, the
  README, and the published `VPN-App.apk` itself (downloaded and read back with `keytool -printcert -jarfile`; `jarsigner -verify`:
  "jar verified"; one signer). The published APK's SHA-256 equals both the release notes and GitHub's own asset digest.
* **Open:** GitHub "release immutability" is not enabled for this repository (the release API reports `immutable: false`), so a
  user with write access could still replace release assets. Recommended: *Settings > General > Releases > Enable release
  immutability* (applies to releases published after it is turned on). Not changed here: it is a repository setting.

## What happened
`tools/release.jks` and its passwords were committed to this public repository, and CI used them. **That signing identity is
compromised and must never be trusted again.** Deleting the file in a newer commit does not help: it stays in git history and
in every clone. Anyone can build an APK that Android treats as a legitimate update to an install signed with it.

## How the key is protected (design)
The key exists in exactly one place: **secrets of the protected GitHub environment `release`**. Ordinary builds never see it.

| Build | Key used | Can read the release secrets? |
|---|---|---|
| Every push to every branch, `workflow_dispatch`, forks | A throw-away key generated inside the job | **No.** No job except `sign` references a secret or a `RELEASE_*` variable (enforced by `tools/check-workflow-security.py`, which fails CI otherwise) |
| Release (a `vMAJOR.MINOR.PATCH` tag on a commit already on the default branch) | The release key, in the `sign` job only | Only after the environment's protection rules pass (required reviewer approval, tag-only deployments) |

Release flow (`.github/workflows/ci.yml`), tags only:
1. The normal jobs run on the tag's commit (engine tests, real-OpenVPN tests, APK build, emulator).
2. `release-gate` (no secrets, read-only): the tag must look like `v1.2.3` and its commit must already be in the default branch's history.
3. `sign` (**the only job with the key**; environment `release`; read-only token): downloads the APK that was just built and tested and
   **re-signs it** with `tools/resign-apk.sh`. It does not build anything or run repository build scripts with the key. It then
   requires that the certificate equals the pinned `RELEASE_CERT_SHA256`, and refuses the old compromised key outright.
4. `publish` (write token, **no secrets**): re-checks the checksum and certificate, records a build-provenance attestation, creates the
   release, and deletes an old mutable `latest` release if one still exists. (Releases are only immutable if the repository setting
   is on; see "Current status".)

`tools/resign-apk.sh` is run on **every** build with a second throw-away key (step "Dry run of the release re-signing script"), and
proves: exactly one signer, v1, v2 and v3 signatures verify for minSdk 23 (Android 6.0 checks only v1, so the release must carry it), no signature from the previous signer survives, every non-META-INF
entry is byte-identical, 16 KB alignment intact. So the release step is exercised before there is a real key.

Other facts: with no secrets configured, **no release is produced** (the `sign` job stops with an explanation). The workflow-wide token
is read-only. Actions are pinned to commit SHAs (one documented exception, see below).

## One-time GitHub setup (completed 25 November 2026; kept for reference and recovery)
These settings live on GitHub; the repository cannot enforce them for you. They are all in place; repeat them only if the repository
is recreated or a setting is found changed. `tools/upload-release-key.sh` re-checks steps 1-3 and 7 every time it runs.

1. **Create the environment.** Repository *Settings > Environments > New environment*, name exactly **`release`**.
2. **Required reviewers.** In that environment enable *Required reviewers* and add yourself. (If you are the only collaborator you can
   approve your own deployments; *Prevent self-review* only helps once there is a second reviewer.) Turn **off** *Allow administrators to
   bypass configured protection rules*.
3. **Restrict where it can deploy.** *Deployment branches and tags > Selected branches and tags > Add deployment branch or tag rule*:
   choose **Tag**, pattern **`v*`**. Do not add any branch rule. This is the control that matters most: even if someone pushes a branch
   whose workflow requests `environment: release`, GitHub refuses to release the secrets to a run from a non-matching ref.
4. **Protect the tags.** *Settings > Rules > Rulesets > New tag ruleset*, target `v*`, enforcement *Active*, rules *Restrict creations*,
   *Restrict updates*, *Restrict deletions*, bypass only for yourself. Then only you can create a release tag.
5. **Protect the default branch.** A ruleset on it with *Restrict deletions*, *Block force pushes*, and (when you can) *Require status
   checks to pass* for `workflow-security`, `engine`, `cert-policy`, `apk (arm64-v8a)`. The release gate already requires the tagged commit
   to be on this branch, so unreviewed history cannot be released.
6. **Actions settings** (*Settings > Actions > General*): *Workflow permissions* = *Read repository contents and packages permissions*;
   *Fork pull request workflows* = require approval for all outside collaborators; if you restrict allowed actions, allow `actions/*`,
   `gradle/actions` and `reactivecircus/android-emulator-runner`.
7. **Do not create repository-level secrets** with the `RELEASE_*` names. CI would ignore them and they would only widen who can read them.
8. Only now: generate the key **on your own computer** and upload it with the two scripts below. Nothing needs to be sent to anyone.

## Generating and uploading the key (done; needed again only to replace a lost or compromised key)
Two scripts, run on your own computer (Windows in **Git Bash**, macOS or Linux in a terminal), from an up-to-date clone of the default
branch:

```bash
bash tools/generate-release-key.sh            # creates ~/.vpnapp-signing/release.p12 and password.txt; prints the certificate SHA-256
bash tools/upload-release-key.sh              # stores them in the protected environment "release", and nowhere else
```

`generate-release-key.sh` needs a JDK (`keytool`; Temurin 21 is tested) and either OpenSSL or `/dev/urandom` (Git Bash has both). It:
* refuses to run if `release.p12` or `password.txt` already exists in the folder, or if the folder is inside a git repository;
* creates a 64-character random password (256 bits; hex digits only, so no line ending or special character can end up in it) and
  hands it to `keytool` through the environment, not the command line;
* builds the RSA-4096 keystore in a private staging folder, opens it again with the saved `password.txt`, checks the certificate
  fingerprint two independent ways, and only then moves **both** files into place; a failure at any step leaves neither file;
* restricts the folder and both files to your account: mode 700/600 on Linux and macOS; on Windows an NTFS access list with
  inheritance removed and a single entry for your account (Unix permissions mean nothing on NTFS, so `chmod` alone would not
  protect anything there), and checks the result;
* prints every step as `[n/7] ...`; if one fails it names the step and shows the tool's own error with passwords removed.

`upload-release-key.sh` (needs `gh`, logged in as an administrator of the repository) checks before uploading that the keystore opens with
`password.txt` and is not the compromised old key, that the environment `release` exists with a required reviewer, administrator bypass
off and deployments limited to tags matching `v*` (no branch rules), and that no **repository-wide** secret with a release name exists. It
then sets the four secrets with `gh secret set ... --env release` (values on standard input, never on a command line) and the public
variable `RELEASE_CERT_SHA256`, and checks afterwards that they are in the environment and not repository-wide. Both scripts are tested in
CI with disposable keys on Linux, macOS and Windows (job `keygen`), including failures and the refusal to overwrite.

**Windows notes.** Use Git Bash (installed with Git for Windows), not PowerShell or `cmd`. The key folder is
`C:\Users\<you>\.vpnapp-signing`. Do not put it in a OneDrive, Dropbox or other synced folder. An earlier version of the generator failed
silently on Windows: OpenSSL there ends its output with a carriage return, which became part of the password, Java rejected it ("Password is
not ASCII"), and the script hid that error. The current version cannot produce such a password, and shows every error.

**Back up both files** (`release.p12` and `password.txt`) offline, for example on an encrypted USB drive. Losing them means users must
uninstall and reinstall to get updates.

First release: merge to the default branch, wait for CI to be green, create the tag (e.g. `v1.0.0`) on that commit, approve the pending
deployment of environment `release` when GitHub asks, and check the release notes show the certificate fingerprint you expect.

## What you can and cannot count on
* **Enforced by the repository:** the job split, the tag-only trigger, the certificate pin, the refusal of the old key, SHA-pinned actions,
  read-only default token (all tested by `tools/test-check-workflow-security.py`, which re-creates each leak and requires it to be rejected).
* **Enforced only by your GitHub settings (steps 1-6):** who may approve, which refs may deploy, who may create tags. If step 3 is skipped,
  a collaborator with write access could push a branch that requests the environment and, if you then approve it, sign something else.
  Read the approval prompt: it shows the ref and commit.
* **Not covered:** a compromised GitHub account of yours, a malicious *approved* change to the workflow or `tools/resign-apk.sh` on the
  default branch (review changes to those files; they are small), and the one unpinned action `actions/attest-build-provenance@v2`
  (GitHub-owned, used in `publish`, which holds no signing secrets; pin it once its commit has been verified).

## Cutting a release
1. Merge to `main` and wait for its CI run to be green (all jobs; see `docs/CI.md` about the macOS job).
2. Tag that commit with an annotated tag `vMAJOR.MINOR.PATCH` and push the tag (only the key holder can create `v*` tags).
3. When GitHub asks, review and approve the pending deployment of environment `release`. The `sign` job then re-signs the APKs built
   and tested in that run and refuses any certificate other than the pinned one; `publish` creates the release.
4. Check the release notes show certificate SHA-256 `0838262d...998a` and that the assets are all present.

## If the key is lost or compromised
* **Lost** (both copies of `release.p12`/`password.txt` gone): no update can be shipped to existing installs. Create a new key
  (section above; the generator refuses to overwrite an existing one), replace the secrets and `RELEASE_CERT_SHA256` with
  `tools/upload-release-key.sh`, publish the new fingerprint in the README, and tell users to uninstall and reinstall.
* **Compromised** (the files or the password may have been seen by someone else): do the same, and additionally add the leaked
  certificate to the `sign` job's refusal list next to the old key, and warn users in the README and release notes.
* Never reuse the old key from `tools/release.jks`; the `sign` job refuses it.

## Migrating existing installs
Android binds an app's identity to its signing certificate. A different key cannot update an install signed with the old one
(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), **except** through APK Signature Scheme v3 key rotation (`apksigner rotate`), which needs the
old key to vouch for the new one.

* **Recommended: uninstall and reinstall once** from a release signed with the new key. Cost: local app data is lost (settings and
  history; all of it is encrypted with a Keystore key that dies with the app anyway). Benefit: the install stops trusting the old key.
* **Rotation was not used, deliberately.** v1.0.0 is signed by the new key alone, with no rotation lineage from the old one, so no
  install signed with the old key can be updated to it: users must uninstall first (the README says so). A lineage would have let
  old installs update, but the old key is public, so anyone could equally create a lineage from it to a key of their own; rotation
  is also unavailable on the Android 6.0-8.1 devices the app supports.
* The old certificate fingerprint is in the git history (`git log --stat -- tools/release.jks`). Treat any APK carrying it as untrusted.
* This only matters to people who installed a build. If you are the only user, reinstalling takes a minute.

## Limits
Provenance attestations and checksums prove *which workflow built a file*, not that the code is safe. The signing key is the root
of trust for updates; protect it as such. Git history still contains the old keystore; rewriting history of a public repo breaks
every clone and does not un-leak anything, so it is not recommended.
