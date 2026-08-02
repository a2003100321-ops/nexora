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

M4.2c validation tag:

```powershell
git tag v0.0.1-test
git push origin v0.0.1-test
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

When signing secrets are missing, the workflow still creates test artifacts, but
the APK names are explicitly marked:

```text
Nexora-mobile-v{version}-unsigned.apk
Nexora-tv-v{version}-unsigned.apk
UNSIGNED_TEST_BUILD.txt
SHA256SUMS.txt
```

Unsigned APKs are only for pipeline validation and internal testing. They are not
production releases.

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
- If GitHub signing secrets are absent, the release is clearly marked
  `unsigned/test`.
- APKs are uploaded to GitHub Releases only; no Play Store publishing is added.
- M4.2c does not start M4.3 or any playback UI work.
