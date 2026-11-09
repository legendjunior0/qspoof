# Changelog

## 5.0

- Refresh the in-app update prompt while retaining verified downloads, sideload installation, and blocking updates.
- Add a separate launcher for the source-based QSpoof Engine and remove its Quick Settings tile.
- Update the QHz tile to show the maximum refresh rate when active.
- Add reversible server-side enforcement of the latest app version and admin usage clusters by build.
- Display the release as v5.0 across the app.

Android `versionCode` remains a monotonically increasing integer for in-place upgrades. Display releases such as 5.1 and 5.3 must use a higher `versionCode` than every earlier release.
