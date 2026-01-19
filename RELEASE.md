# JReleaser Release Setup

This document describes how to configure and use the JReleaser automation for this project.

## Prerequisites

Before triggering a release, ensure you have configured the following GitHub secrets:

### Required Secrets

1. **MAVEN_USERNAME**: Your Sonatype OSSRH username
   - Get this from https://s01.oss.sonatype.org/

2. **MAVEN_PASSWORD**: Your Sonatype OSSRH password
   - This is your Sonatype OSSRH user token (preferred) or password

3. **GPG_PRIVATE_KEY**: Your GPG private key (base64 encoded)
   - Export your key: `gpg --export-secret-keys YOUR_KEY_ID | base64`
   - Or use: `gpg --export-secret-keys --armor YOUR_KEY_ID | base64`

4. **GPG_PASSPHRASE**: The passphrase for your GPG key
   - This is the password you use to unlock your GPG key

5. **GPG_PUBLIC_KEY**: Your GPG public key
   - Export: `gpg --export --armor YOUR_KEY_ID`

### Setting Up Secrets

1. Go to your repository on GitHub
2. Navigate to Settings → Secrets and variables → Actions
3. Click "New repository secret"
4. Add each of the secrets listed above

## Generating GPG Keys (if you don't have one)

```bash
# Generate a new GPG key
gpg --full-generate-key

# List your keys to get the KEY_ID
gpg --list-secret-keys --keyid-format=long

# Export the public key for upload to key servers
gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_ID

# Export for GitHub secret (base64 encoded)
gpg --export-secret-keys --armor YOUR_KEY_ID | base64 -w 0
```

## Triggering a Release

1. Go to the **Actions** tab in your GitHub repository
2. Select the **Release** workflow from the left sidebar
3. Click **Run workflow** (on the right side)
4. Enter the release version (e.g., `1.0.0`, `1.1.0`, etc.)
5. Click **Run workflow**

## What Happens During Release

The workflow will:

1. **Set Version**: Update all POM files to the release version
2. **Build**: Compile and verify the project
3. **Sign**: Sign all artifacts with GPG
4. **Stage**: Deploy artifacts to the local staging directory
5. **Release to Maven Central**: Deploy only the `netty-virtualthread-core` artifact to Maven Central
6. **Create GitHub Release**: Create a GitHub release with changelog
7. **Bump Version**: Automatically increment the version and add `-SNAPSHOT` suffix
8. **Commit & Push**: Push the new SNAPSHOT version back to the master branch

## Release Artifacts

- **Published to Maven Central**: `netty-virtualthread-core` only
- **Not Published**: `benchmarks`, `example-echo` (versions are still bumped to SNAPSHOT)

## Configuration Files

- `.github/workflows/release.yml`: GitHub Actions workflow
- `.mvn/jreleaser.yml`: JReleaser configuration
- `pom.xml`: Maven configuration with release profile

## Troubleshooting

### Release fails at Maven Central stage

- Check that MAVEN_USERNAME and MAVEN_PASSWORD are correct
- Ensure you have proper permissions in Sonatype OSSRH
- Check the staging repository at https://s01.oss.sonatype.org/

### Release fails at GPG signing

- Verify GPG_PRIVATE_KEY is properly base64 encoded
- Check that GPG_PASSPHRASE is correct
- Ensure the GPG key is not expired

### GitHub release creation fails

- The GITHUB_TOKEN is automatically provided by GitHub Actions
- Check repository permissions in Settings → Actions → General

## Manual Cleanup

If a release fails partway through:

1. Delete the Git tag if created: `git push --delete origin TAG_NAME`
2. Delete the GitHub release if created (in Releases section)
3. Drop the staging repository in Sonatype if artifacts were staged
4. Reset version in POM files: `mvn versions:set -DnewVersion=X.Y.Z-SNAPSHOT`

## Support

For issues with:
- JReleaser: https://github.com/jreleaser/jreleaser/discussions
- Maven Central: https://central.sonatype.org/support/
- GPG: https://www.gnupg.org/documentation/
