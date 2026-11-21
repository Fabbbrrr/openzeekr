# Fork build — how updates reach the phone (fork-only doc)

This fork (`Fabbbrrr/openzeekr`) exists so Fab's install can update itself without waiting for
the author to run his own signing machine. Upstream stays untouched; everything here lives only
on the `fab/fork-build` branch.

## The deal

- The phone runs a build **signed with the fork key** (the one in the fork repo's GitHub Actions
  secrets, cert SHA-256 `55ef455f…e0b20b`). Every future fork release is signed with that same
  key, so updates install **over** the current app — no uninstall, no data loss.
- The app's update check reads **this repo** and only offers releases whose tag carries the
  `fab` marker (`gradle.properties`: `openzeekr.updateRepo`, `openzeekr.updateMarker`; enforced
  in `UpdateChecker`). An upstream release can never be pushed onto this install, and this
  repo's releases are never offered to a stock-signed install.
- The update prompt opens the release page (upstream's design — the app never downloads or
  installs by itself). The release must be **published, not draft**: the anonymous API the app
  uses never returns drafts.

## Version rule

`versionName = <upstream>-fab.<n>`, `versionCode = <upstream code>*100 + <n>`.
Example: upstream 0.2.7 (code 19) → fork `0.2.7-fab.1` (code 1901). Never let versionCode go
backwards on this branch — a lower code cannot install over a higher one.

## Releasing a new upstream version

1. `git fetch upstream && git merge <new-tag>` on `fab/fork-build`, resolve, bump version per the
   rule above.
2. `./gradlew :app:assembleRelease` locally with the real `secrets.properties` /
   `secrets_firebase.xml` / `keystore.properties` (all gitignored).
3. Commit, push the branch, tag `v<x.y.z>-fab.<n>` on that commit, push the tag.
4. The `release-fork` workflow builds with the fork key and publishes the release. The phone's
   next launch (or Settings → check updates) offers it.

## Fork secrets (GitHub Actions → Secrets and variables)

`OZ_KEYSTORE` (base64 of the .jks), `OZ_KEYSTORE_PASSWORD`, `OZ_KEY_ALIAS`, `OZ_KEY_PASSWORD`,
`OZ_SECRETS_PROPERTIES` (file contents), `OZ_SECRETS_FIREBASE_XML` (file contents).

## What this costs

- The fork's APK is not signed by the author, so it is a different app identity from his —
  switching between them means uninstall + re-login (uninstall wipes the Keystore-encrypted
  config).
- The fork's release notes link upstream's changelog; they are not re-authored here.
- Two files (`gradle.properties`, the workflow) plus the UpdateChecker/version patches are
  fork-only and must never be sent upstream as PRs.
