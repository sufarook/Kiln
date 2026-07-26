# Releasing Kiln to Maven Central

This guide covers the one-time account setup and the repeating release process.

---

## One-time setup

### 1. Sonatype Central Portal account

1. Go to <https://central.sonatype.com> and sign in with **GitHub OAuth** using your `sufarook` account.
2. After sign-in, click **Namespaces** → **Add Namespace** → enter `io.github.sufarook`.
3. Sonatype will verify the namespace automatically against your GitHub account. No DNS or domain required.
4. Once the namespace shows **Verified**, you can publish artifacts under `io.github.sufarook.*`.

### 2. Generate a GPG key for signing

```bash
gpg --full-generate-key
```

Choose:
- Key type: **RSA and RSA**
- Key size: **4096**
- Expiry: **2y** (or never)
- Real name: `Syed Ummer Farook`
- Email: `syedfarook1798@gmail.com`

Export the ASCII-armored private key (this value goes into GitHub Secrets):

```bash
gpg --armor --export-secret-keys syedfarook1798@gmail.com | pbcopy
```

Upload the **public** key so Maven Central can verify signatures:

```bash
gpg --keyserver keyserver.ubuntu.com --send-keys <YOUR_KEY_ID>
```

Get the key ID with:

```bash
gpg --list-secret-keys --keyid-format LONG
# sec   rsa4096/<KEY_ID_HERE> 2025-...
```

### 3. Create a Sonatype token

1. In Central Portal → **Account** → **Generate User Token**.
2. Copy the **username** and **password** (shown only once).

### 4. Add GitHub Actions secrets

In your repo at **Settings → Secrets and variables → Actions**, add:

| Secret name | Value |
|---|---|
| `SIGNING_KEY` | The full ASCII-armored GPG private key (`-----BEGIN PGP PRIVATE KEY BLOCK-----` ... `-----END PGP PRIVATE KEY BLOCK-----`) |
| `SIGNING_PASSWORD` | The passphrase you set during GPG key generation |
| `MAVEN_CENTRAL_USERNAME` | Username from the Sonatype token (step 3) |
| `MAVEN_CENTRAL_PASSWORD` | Password from the Sonatype token (step 3) |

---

## Publishing a release

### Local smoke-test first

```bash
./gradlew publishToMavenLocal
```

This publishes all artifacts to `~/.m2/` without signing. Use it to verify the POM content and artifact layout look correct before pushing.

### Tag and release

```bash
# Make sure you're on main and tests pass
git checkout main
git pull

# Create an annotated tag — the CI publish job triggers on tags matching v*
git tag -a v1.0.0-alpha01 -m "Release 1.0.0-alpha01"
git push origin v1.0.0-alpha01
```

The **Publish to Maven Central** CI job:
1. Runs only when a `v*` tag is pushed.
2. Waits for all four test jobs to pass.
3. Calls `./gradlew publishAllPublicationsToMavenCentralRepository` with signing enabled.

### After the CI publish job succeeds

1. Go to <https://central.sonatype.com> → **Deployments**.
2. The deployment starts in **PENDING** state. Click **Publish** to promote it to Maven Central.
3. Artifacts appear on Maven Central within ~10 minutes of publishing.

---

## Version policy

| Version suffix | Meaning |
|---|---|
| `1.0.0-alpha01` | Early access — public API may change |
| `1.0.0-beta01` | Feature-complete, API stabilising |
| `1.0.0` | Stable — no API breaks without a major version bump |

To cut a new release, update the version in the root [`build.gradle.kts`](build.gradle.kts):

```kotlin
allprojects {
    group = "io.github.sufarook.kiln"
    version = "1.0.0-alpha02"   // ← bump here
}
```

Update the same version string everywhere it appears in docs before tagging:

```bash
grep -r "1\.0\.0-alpha01" docs/ README.md
```
