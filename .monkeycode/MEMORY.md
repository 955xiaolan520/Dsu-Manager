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
