# User Instruction Memory

This file records user instructions, preferences, and teachings for reference in future interactions.

## Format

### User Instruction Entry
User instruction entries should follow this format:

[User Instruction Summary]
- Date: [YYYY-MM-DD]
- Context: [Mentioned scenario or time]
- Instructions:
  - [Content of user teaching or instruction, described line by line]

### Project Knowledge Entry
Entries discovered by the Agent during task execution should follow this format:

[Project Knowledge Summary]
- Date: [YYYY-MM-DD]
- Context: Discovered by Agent while performing [specific task description]
- Category: [Operations & Deployment|Build Methods|Testing Methods|Troubleshooting & Debugging|Workflow & Collaboration|Environment Configuration]
- Instructions:
  - [Specific knowledge points, described line by line]

## Deduplication Strategy
- Before adding a new entry, check for similar or identical instructions.
- If a duplicate is found, skip the new entry or merge it with the existing one.
- When merging, update the context or date information.

## Entries

[Project Knowledge Summary]
- Date: 2026-09-24
- Context: Discovered by Agent while preparing Android SDK 37 build environment (user requires Android 17 / SDK 37)
- Category: Environment Configuration
- Instructions:
  - Android SDK installed at /opt/android-sdk (cmdline-tools latest, platform-tools 37.0.1, build-tools 34.0.0 + 37.0.0); local.properties sdk.dir already points there
  - SDK 37 platform package is named platforms;android-37.0 (Android 17, r02); AGP 8.5.2 resolves directory android-37, so a symlink platforms/android-37 -> android-37.0 was created
  - Signing config in app/build.gradle: app/release-keystore.jks, password changeit, alias tianming-release (V1+V2+V3); the root-level release-keystore.jks must be copied into app/ before release build
  - Gradle 8.7 wrapper already warmed up; AGP 8.5.2 + Kotlin 1.9.22 plugin dependencies cached in ~/.gradle
  - AGP 8.5.2 compatibility: /opt/android-sdk/platforms/android-37.0 package.xml was edited (path=platforms;android-37, api-level 37) and source.properties (AndroidVersion.ApiLevel=37) so AGP resolves compileSdk 37; platforms/android-37 symlink points to android-37.0. Reinstalling platforms;android-37.0 via sdkmanager would recreate a duplicate 37.0 dir with original metadata
  - Build verification command: ./gradlew :app:assembleDebug (BUILD SUCCESSFUL confirmed on 2026-09-24); release signing expects app/release-keystore.jks in place

[Project Knowledge Summary]
- Date: 2026-09-24
- Context: User instruction during environment preparation session
- Category: Workflow & Collaboration
- Instructions:
  - User uploads signing key release-keystore.jks to workspace themselves; agent must not generate or replace it
  - Environment preparation must be confirmed with the user before running any build commands; wait for explicit user instruction
