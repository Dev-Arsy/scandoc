# ScanDoc

Offline document scanner for Android 8.0+ (works on MIUI 12). No ads, no account, no internet needed after setup.

- Auto edge detection, perspective crop, shadow removal and filters (Google ML Kit Document Scanner)
- Multi-page scans, import from gallery
- Export as **PDF** and **Word (.docx)** (on-device OCR, editable text)
- Share or save anywhere through the system file picker

## Get the APK (pick one)

### A. GitHub Actions (no Android Studio needed)
1. Create a new GitHub repo and upload everything in this folder (keep the `.github` folder).
2. Open the **Actions** tab, then **Build APK**, then **Run workflow** (it also runs on every push).
3. When it finishes (about 3-5 minutes), download the `ScanDoc-apk` artifact, unzip it, and install `app-release.apk` on the phone.

### B. Android Studio
1. Open this folder in Android Studio (Hedgehog or newer) and let Gradle sync.
2. Build > Build Bundle(s) / APK(s) > Build APK(s). Or plug in the phone and press Run.

## Install on MIUI 12
Open the APK from Files. If asked, allow "Install unknown apps" for the app you opened it from.
The phone needs Google Play Services (standard on global/Indonesia ROMs).

## Notes
- The APK is signed with a debug key. A new build from a different machine has a different key, so uninstall the old version before installing it.
- Word export reads text only (Latin script, includes Indonesian and English). Tables and images become plain paragraphs.
