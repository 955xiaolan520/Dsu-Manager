# Third-party notices

## YunX (云析)

- **Upstream:** <https://github.com/CYQawa/YunX>
- **Imported revision:** `bdafe9ee056be47d9e1567203e07312046df679b` (2026-10-10 snapshot)
- **Imported source:** `yunx/src/main/kotlin/`, `yunx/src/main/res/`, and the accompanying module resources.
- **Copyright:** Copyright (C) 2026 CYQawa.
- **License:** GNU Affero General Public License, version 3 or later (AGPL-3.0-or-later); full text is included in [`LICENSE`](LICENSE) and packaged in the app as `AGPL-3.0.txt`.
- **Integration changes:** The upstream Android app source was adapted into the `:yunx` Android library module. Dsu-Manager keeps its own `Application`, launcher, signing configuration, and `FileProvider`; the ROM page opens the embedded YunX parser. Embedded mode suppresses YunX's standalone onboarding and automatic update/announcement requests, and supplies a Dsu/DNA-styled workspace with account, settings, and bookmark access. **All** embedded YunX HTTP(S) download submissions, including logged-in drive browsing and share parsing, are intercepted before YunX's task database/service and created as native Dsu `DownloadService` tasks. They use the existing `Aria2Downloader`/aria2c runner with platform-specific request headers, are visible in Dsu's Download Manager, and default to `/storage/emulated/0/Download/DsuManager/Yunpan`. Request headers are encrypted with Android Keystore while persisted for pause/resume. OkHttp is retained for YunX API/login and small metadata probes only. HLS/m3u8 and magnet/BT transfers are explicitly rejected; there is no fallback to a different transfer engine. The embedded settings surface keeps download location, clipboard parsing, proxy, and account credential backup while hiding host-inapplicable appearance controls and YunX-only download-engine/tuning pages.

## Gopeed Java bridge classes

- **Upstream project:** <https://github.com/GopeedLab/gopeed>
- **Included file:** [`yunx/libs/gopeed-classes.jar`](yunx/libs/gopeed-classes.jar), a small Java bridge API used by YunX's optional Gopeed engine integration. The Gopeed native engine binary is not included.
- **License:** GNU General Public License, version 3 (GPL-3.0); the upstream license is included at [`yunx/licenses/Gopeed-GPL-3.0.txt`](yunx/licenses/Gopeed-GPL-3.0.txt).
- **Use in this app:** The bridge classes remain in the upstream module snapshot, but the embedded Dsu-Manager UI disables Gopeed selection and does not invoke the Gopeed runtime. All downloads created by embedded YunX workflows are routed to Dsu's native aria2c task service; the YunX foreground download service is not started for those transfers.

## Distribution note

This repository now includes and modifies YunX source as a combined Android application. The complete corresponding source is present in this repository. The Dsu-Manager source and integrated YunX module are distributed under AGPL-3.0-or-later; separate third-party components retain the licenses identified above.
