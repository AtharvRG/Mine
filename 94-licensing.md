
# Licensing

Status: core     Version: 0.1     Depends on: 16-security-privacy, 92-versioning

## 1. Purpose

Mine and its blueprints are released under the **GNU General Public License, version 3**. This file records what
that means in practice: which files get which notices, what to do with dependencies and downloaded models, how
contributions are accepted, and what the license does not cover. It is a working note, not legal advice.

## 2. Behavior (what the user sees)

- The About screen shows: the license name, a link to the source on GitHub, and an "Open-source licenses" list of dependencies.
- Every GitHub release links to the tagged source for that exact build.
- The model download screen shows the model's own terms before downloading.

## 3. Design

### 3.1 The choice

- **License:** GPL-3.0-or-later. The identifier in files is `GPL-3.0-or-later`.
- **Scope:** the app source code, build scripts, and the `blueprints` branch (documents under the same license, so there is one license to explain).
- **What GPL means here:** anyone may use, study, change and share the app, including for money, as long as they pass on the same freedoms and publish their changes' source under GPLv3 when they distribute them. GPL does **not** let you limit who downloads it or what they use it for. A "non-commercial only" restriction would make it a different, non-free license.

### 3.2 Files in the repositories

| File                        | Where                                                        | Content                                                                                                                                                                  |
| --------------------------- | ------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `LICENSE`                 | Root of the code repo, and root of the `blueprints` branch | The **unmodified** official GPLv3 text from https://www.gnu.org/licenses/gpl-3.0.txt. Do not retype it, and do not edit it. GitHub recognizes it by the exact text |
| `THIRD_PARTY_LICENSES.md` | Root of the code repo                                        | Each dependency, its version, its license, and its link                                                                                                                  |
| `NOTICE` (optional)       | Root                                                         | Short copyright line and the notice in 3.3                                                                                                                               |
| About screen data           | App assets                                                   | Generated from `THIRD_PARTY_LICENSES.md` at build time                                                                                                                 |

### 3.3 Notices in source files

Each source file starts with this short header (SPDX form):

```text
// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 <Your Name or Handle>
// Part of Mine. See LICENSE in the repository root.
```

The README and `NOTICE` carry the standard FSF notice:

```text
Mine is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3
of the License, or (at your option) any later version.

Mine is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License along with Mine. If not,
see <https://www.gnu.org/licenses/>.
```

Blueprint files do not need a per-file header. The README of the branch states the license.

### 3.4 Dependencies

Rule: a dependency may be added only if its license is compatible with GPLv3 and is listed in `THIRD_PARTY_LICENSES.md` in the same PR.

| Component                                            | Expected license                                                    | Note                                                                                                                                 |
| ---------------------------------------------------- | ------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| Kotlin, AndroidX, Jetpack Compose, Room, WorkManager | Apache 2.0                                                          | Compatible with GPLv3 (one direction: Apache code can go into a GPLv3 app). Not compatible with GPLv2-only, which is irrelevant here |
| SQLCipher (community edition)                        | BSD-style                                                           | Compatible. Requires its attribution notice to be shown                                                                              |
| Tink (if used)                                       | Apache 2.0                                                          | Compatible                                                                                                                           |
| sherpa-onnx, ONNX Runtime (v2+)                      | Apache 2.0 / MIT                                                    | Compatible. **Verify** at the time of use                                                                                      |
| Silero VAD (v3)                                      | MIT                                                                 | Compatible. **Verify**                                                                                                         |
| espeak-ng data used by some TTS builds (v2)          | GPLv3                                                               | Compatible with a GPLv3 app. **Verify** which build is used                                                                    |
| **Avoid**                                      | AGPL, "non-commercial" licenses, source-available licenses, unknown | Not compatible, or not free                                                                                                          |

All versions and licenses must be **verified from each project's own repository** before each release. The table above is a starting point and may have changed.

### 3.5 Model files are not part of the code license

- **EmbeddingGemma 2** is distributed under Google's own terms (the Gemma terms of use), not under GPL and not under a standard open-source license. **Verify** the current terms for the exact model version.
- Therefore: **do not commit or bundle model weights** in the repo or the APK. The app downloads them (or the user imports them) and shows the model's terms first. The repo only contains the code that loads them.
- The same applies to speech models (Kokoro and others) in v2 and v3. Record each model's license in `THIRD_PARTY_LICENSES.md` under "Models (downloaded, not distributed)".

### 3.6 Source for every release (GPLv3 duty when distributing the APK)

- Every APK on GitHub Releases has a matching **tag** in the code repo, and the tag stays available for as long as the APK is offered.
- Optionally attach the source archive to the release as well.
- Build instructions are in the repo, so a third party can build the same app from the tagged source.
- The signing key is **not** part of the source and is never published (`16`).

### 3.7 Contributions

- **Inbound equals outbound:** contributions are accepted under GPLv3 (or later), the same as the project.
- Use the **Developer Certificate of Origin**: contributors add `Signed-off-by:` to commits. No separate agreement to sign.
- Contributions are accepted under GPLv3-or-later through the DCO; no separate contributor agreement is required.

### 3.8 What the license does not cover

- **Names and marks:** "Mine", "MineAI" and the logo are not licensed by GPL. Add a short note in the README that forks should use a different name and icon if they publish builds, so users do not confuse them with the original.
- **User data:** exports, memories and chats belong to the user. The license says nothing about them.
- **Provider and model terms:** each AI endpoint's own terms apply to what the user sends through it. Mine only reminds the user, it does not change them (`16` 3.3).
- **Warranty:** GPL already disclaims it. The app does not give medical, legal or crisis advice (`16` 3.7).

## 4. Data

None. `app_meta` stores nothing about licensing.

## 5. Settings

None. About > Open-source licenses is read-only.

## 6. Edge cases and failure modes

- **A dependency changes license** in a new version. Pin versions and re-check on each upgrade.
- **Someone forks and ships a closed build.** That breaks the GPL. The remedy is a polite request to publish the source, and a GitHub takedown notice if needed.
- **A contributor submits code they did not write** or that has another license. Reject it, and keep the sign-off requirement.
- **The model terms change.** Because weights are downloaded, not bundled, only the download screen text and `THIRD_PARTY_LICENSES.md` change.

## 7. Offline behavior

The About screen and the license list are bundled and work offline. Model terms are shown before the download, and cached afterwards.

## 8. Tests

1. About screen shows the license, the source link and the dependency list.
2. Build fails (or warns) if a dependency is missing from `THIRD_PARTY_LICENSES.md`.
3. Release checklist (`92` 3.6) step 5: the tag exists and matches the APK.
4. The APK contains no model weights, and the download screen shows the terms.
5. Every source file has the SPDX header (a simple script check in CI).

## 9. Open questions

- None. The project uses GPL-3.0-or-later for the code and blueprints, with DCO sign-off; model weights are downloaded under their own terms.

## 10. Changelog

- 0.1 (2026-10-21): first draft.
