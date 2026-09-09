# User Instruction Memory

This file records user instructions, preferences, and teachings for reference in future interactions.

## Entries

### User Instruction Summary
- Date: 2026-08-28
- Context: Ongoing repository development
- Instructions:
  - Use the shared workspace and continue tasks through implementation and verification when the next step is clear.
  - Use Glob and Grep for repository searches and parallelize independent tool calls.
  - Use apply_patch for manual file edits and preserve unrelated worktree changes.
  - Keep responses concise, in Simplified Chinese, and free of emoji characters.

### Project Knowledge Summary
- Date: 2026-08-28
- Context: Discovered while preparing the Dsu Manager release
- Category: Build Methods
- Instructions:
  - Release builds use `/workspace/.tools/gradle/gradle-8.7/bin/gradle` with the Android SDK at `/workspace/.tools/android-sdk`.
  - Run Android build and packaging commands through a resource-limited background terminal.

### User Instruction Summary
- Date: 2026-09-02
- Context: GitHub commit attribution for the Dsu Manager repository
- Instructions:
  - Configure repository commits with the user's GitHub identity `955xiaolan520 <955xiaolan520@users.noreply.github.com>`.

### User Instruction Summary
- Date: 2026-09-06
- Context: Dsu Manager GitHub release workflow
- Category: Workflow & Collaboration
- Instructions:
  - Synchronize all Dsu Manager-related source code to GitHub for each release.
  - Publish both Debug and Release APK artifacts with the corresponding release.

### User Instruction Summary
- Date: 2026-09-08
- Context: OPlus OTA internal downloader debugging
- Instructions:
  - Keep OPlus downloads inside the Dsu Manager internal downloader using the existing DownloadService and JavaDownloader flow.
  - Do not switch to Android DownloadManager or change the download approach without explicit confirmation.
  - Investigate the server authorization and request format before changing the downloader implementation.
