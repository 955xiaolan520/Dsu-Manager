# Third-party notices

## YunX (云析)

- **Upstream:** <https://github.com/CYQawa/YunX>
- **Imported revision:** `bdafe9ee056be47d9e1567203e07312046df679b` (2026-10-10 snapshot)
- **Imported source:** `yunx/src/main/kotlin/`, `yunx/src/main/res/`, and the accompanying module resources.
- **Copyright:** Copyright (C) 2026 CYQawa.
- **License:** GNU Affero General Public License, version 3 or later (AGPL-3.0-or-later); full text is included in [`LICENSE`](LICENSE) and packaged in the app as `AGPL-3.0.txt`.
- **Integration changes:** The upstream Android app source was adapted into the `:yunx` Android library module. Dsu-Manager keeps its own `Application`, launcher, signing configuration, and `FileProvider`; embedded mode supplies a Dsu/DNA-styled workspace. Ordinary embedded HTTP(S) share downloads are intercepted and created as native Dsu `DownloadService` tasks using aria2c, with platform request headers encrypted by Android Keystore while persisted. BT/magnet tasks are a separate Gopeed-backed path: the user downloads/imports the Gopeed native kernel on demand from the engine page; it is not bundled into the APK. Magnet metadata is resolved by Gopeed, local `.torrent` files are parsed on-device, and selected file indexes are submitted to Gopeed while task status is mirrored in Dsu's Download Manager. The Dsu-native file browser is used to select local torrent files. The embedded settings exposes a Gopeed BT/kernel entry while normal HTTP(S) tasks remain on Dsu aria2c.

## Gopeed Java bridge classes

- **Upstream project:** <https://github.com/GopeedLab/gopeed>
- **Included file:** [`yunx/libs/gopeed-classes.jar`](yunx/libs/gopeed-classes.jar), a small Java bridge API used by YunX's optional Gopeed engine integration. The Gopeed native engine binary is not included.
- **License:** GNU General Public License, version 3 (GPL-3.0); the upstream license is included at [`yunx/licenses/Gopeed-GPL-3.0.txt`](yunx/licenses/Gopeed-GPL-3.0.txt).
- **Use in this app:** The Java bridge is included, but Gopeed's native engine is not. Magnet resolution and BT transfers require the user to install the matching ABI kernel separately through the in-app Gopeed engine page. BT downloads are routed through Gopeed and surfaced in Dsu's persistent download manager; ordinary HTTP(S) share downloads continue to use Dsu aria2c.

## Feature source acknowledgments

- **Baidu anonymous public-share parsing:** Flow and API behavior were referenced from <https://github.com/z7786/wenxi> (`lib/data/providers/baidu_public_parser.dart` and related password/command helpers). The Android implementation is a native adaptation; it sends the share URL and extraction code to the third-party parser service `https://mf.dg22.top`, never the user's Baidu account cookie. The feature is limited to one file smaller than 10 GiB.
- **Torrent / magnet downloads:** The REST resolve/task flow and selected-file behavior follow the public Gopeed API and upstream project at <https://github.com/GopeedLab/gopeed> and <https://gopeed.com/docs/openapi>. Gopeed kernel distribution is from <https://github.com/CYQawa/yunx_gopeed_build> and is fetched on demand by ABI, not included in this APK.
- Thanks to the upstream authors and maintainers for making these projects available under their respective open-source terms. The wenxi repository is used as an implementation reference, not bundled as a dependency; Gopeed's Java bridge/native runtime retain the GPL-3.0 terms noted above.

## Distribution note

This repository now includes and modifies YunX source as a combined Android application. The complete corresponding source is present in this repository. The Dsu-Manager source and integrated YunX module are distributed under AGPL-3.0-or-later; separate third-party components retain the licenses identified above.
