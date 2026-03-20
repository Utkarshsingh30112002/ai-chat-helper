# APK for install

After building the debug app, copy the APK here so Git can track **only this file** (not the whole `build/` tree):

From the **repo root**:

```bash
cp android/app/build/outputs/apk/debug/app-debug.apk releases/app-debug.apk
git add releases/app-debug.apk
```

From inside **`releases/`**:

```bash
cp ../android/app/build/outputs/apk/debug/app-debug.apk ./app-debug.apk
git add app-debug.apk
```

Rename or replace `app-debug.apk` whenever you rebuild. The main `.gitignore` still ignores `android/**/build/`.
