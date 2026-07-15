# Legacy configuration corpus

This directory contains synthetic, text-only fixtures for Nexora's M0-M2 legacy
configuration compatibility work. It deliberately contains no configuration copied from
PickTV, no user data, and no third-party plugin payload.

## Safety boundary

- Fixtures are data for corpus inventory tests. They are not executed or fetched.
- JAR, JavaScript, Python, AAR, native libraries, archives, bytecode, and executables are
  forbidden even when their contents are plain text.
- Plugin URLs in `plugin-descriptors-only.json` are inert strings using the reserved
  `example.invalid` domain. They test descriptor inventory only.
- `live`, `notice`, and `drm` examples must be preserved as compatibility data but remain
  disabled by product policy.
- A fixture must be synthetic, listed in `manifest.json`, UTF-8 without BOM, no larger than
  256 KiB, and protected by its SHA-256 digest.

Run the guard with:

```text
gradlew :source:testkit:test
```

Do not add real legacy configurations during M0-M2. A future sanitized-real corpus requires
its own review, provenance rules, and ADR.
