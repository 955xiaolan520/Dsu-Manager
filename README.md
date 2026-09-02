# Dsu Manager

Dsu Manager is an Android utility for managing Dynamic System Updates (DSU) and GSI images on rooted devices. It provides ROOT and GSI status checks, ZIP-based GSI installation, userdata sizing, DSU boot and removal actions, installed image management, single-image replacement, custom background artwork, multilingual settings, and a GitHub Releases update entry.

## Version 3.2.1

- Restored automatic GSI summary display after installation.
- Persisted the installed ZIP name so the GSI summary survives app restarts.
- Removed periodic post-install status polling to reduce battery usage.
- Restored and refined the installed image management workflow.
- Added lossless replacement support for all discovered DSU image files.
- Added GitHub latest-release checking in Settings.
- Added release notes display and a direct latest Release APK download button.
- Updated the home logo depth styling, settings cards, rounded progress bar, and multilingual labels.

## Build

The project uses Gradle and Android SDK 37.

```bash
# Build the release APK
./gradlew assembleRelease
```

The release signing keystore is intentionally excluded from the repository. Configure a local signing key before producing a distributable release APK.

## Requirements

- Android Studio or a compatible Android SDK and Gradle installation
- Android 10 or newer on the target device
- ROOT access for DSU operations
- A device with Dynamic System support

## License and acknowledgements

This project is an independent utility built around Android's public Dynamic System APIs and documented DSU workflows. The application does not include the source code or binary implementation of third-party applications.
