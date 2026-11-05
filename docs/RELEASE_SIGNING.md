# Release signing (finding B1)

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
   immutable release, and deletes the old mutable `latest` release (which carries the leaked-key APK).

`tools/resign-apk.sh` is run on **every** build with a second throw-away key (step "Dry run of the release re-signing script"), and
proves: exactly one signer, v2 and v3 signatures verify for minSdk 26, no signature from the previous signer survives, every non-META-INF
entry is byte-identical, 16 KB alignment intact. So the release step is exercised before there is a real key.

Other facts: with no secrets configured, **no release is produced** (the `sign` job stops with an explanation). The workflow-wide token
is read-only. Actions are pinned to commit SHAs (one documented exception, see below).

## One-time GitHub setup (do this BEFORE uploading the key)
These settings live on GitHub; the repository cannot enforce them for you. In this order:

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
8. Only now: run `tools/generate-release-key.sh` **on your own machine**; it prints the commands that add the four secrets and the public
   variable `RELEASE_CERT_SHA256` to the **environment** (`gh secret set ... --env release`). Nothing needs to be sent to anyone.

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

## To do (needs you; I cannot and should not create or hold your key)
0. **Delete the public `latest` release (build #17) now.** It is still downloadable, it carries an APK signed with the leaked key, and the README's install link points at it. Nothing available to the authoring work can delete a release. (GitHub > Releases > `latest` > Delete.)
1. Complete the GitHub setup above (steps 1-6).
2. On your own machine run `tools/generate-release-key.sh` (it prints the public fingerprint and the exact `gh` commands).
3. Back the key up offline. Losing it means you can never ship an update Android will accept for installs signed with it.
4. Publish the fingerprint (README, release notes). Users compare it before installing.

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
