# Nexora APK Release Pipeline

Nexora publishes Android APKs through GitHub Actions and GitHub Releases.

## Release Workflow

Workflow file:

```text
.github/workflows/release.yml
```

The workflow runs in two ways:

- manually from GitHub Actions with `workflow_dispatch`
- automatically when a tag matching `v*` is pushed

Example production-style tag:

```powershell
git tag v1.0.0
git push origin v1.0.0
```

M4.2c validation tags:

```powershell
git tag v0.0.2-test
git push origin v0.0.2-test
```

## APKs Built

The release workflow builds:

- `:app-mobile:assembleRelease`
- `:app-tv:assembleRelease`

When signing secrets are configured, the release assets are named:

```text
Nexora-mobile-v{version}.apk
Nexora-tv-v{version}.apk
SHA256SUMS.txt
```

When signing secrets are missing, the workflow creates installable test artifacts
with an ephemeral CI-generated test key. The APK file names stay stable:

```text
Nexora-mobile-v{version}.apk
Nexora-tv-v{version}.apk
TEST_SIGNED_BUILD.txt
SHA256SUMS.txt
```

Test-signed APKs can be installed for pipeline validation, but they are not
production releases. A production release must use the repository signing
secrets.

The workflow verifies every APK before publishing:

```text
apksigner verify
unzip -t
sha256sum --check
```

The earlier `v0.0.1-test` pipeline produced unsigned APKs:

```text
Nexora-mobile-v0.0.1-test-unsigned.apk
Nexora-tv-v0.0.1-test-unsigned.apk
UNSIGNED_TEST_BUILD.txt
SHA256SUMS.txt
```

Those unsigned artifacts were valid ZIP files but not installable Android
packages. `v0.0.2-test` replaces that validation path with installable
test-signed APKs.

## Signing Secrets

Do not commit keystores, passwords, or signing files.

Configure these GitHub repository secrets before publishing a production release:

```text
NEXORA_KEYSTORE_BASE64
NEXORA_KEYSTORE_PASSWORD
NEXORA_KEY_ALIAS
NEXORA_KEY_PASSWORD
```

`NEXORA_KEYSTORE_BASE64` is the Base64-encoded Android keystore file.

Example local encoding command on PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\release.keystore")) |
  Set-Content -NoNewline "release.keystore.base64.txt"
```

Copy the file content into the GitHub secret. Do not commit the generated
`.base64.txt` file.

## Creating a Release

1. Make sure the release commit is pushed to GitHub.
2. Create and push a tag starting with `v`.
3. Wait for the `APK Release` workflow to finish.
4. Open the repository Releases page.
5. Download the mobile or TV APK from the release assets.

Repository Releases page:

```text
https://github.com/a2003100321-ops/nexora/releases
```

## SHA256 Verification

Each release contains `SHA256SUMS.txt`.

Download the APK and `SHA256SUMS.txt`, then verify locally:

```powershell
Get-FileHash .\Nexora-mobile-v1.0.0.apk -Algorithm SHA256
Get-Content .\SHA256SUMS.txt
```

The hash shown by `Get-FileHash` must match the corresponding APK entry in
`SHA256SUMS.txt`.

## Current M4.2c Limitations

- No keystore is committed.
- If GitHub signing secrets are absent, the release is signed with a temporary
  CI test key and clearly marked `test-signed`.
- APKs are uploaded to GitHub Releases only; no Play Store publishing is added.
- M4.2c does not start M4.3 or any playback UI work.
