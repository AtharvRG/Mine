# H1 — Live API key in release BuildConfig

Status: **fixed and verified** (2026-11-07)

## Defect

`app/build.gradle.kts` declared the live-provider credentials in `defaultConfig`:

```kotlin
buildConfigField("String", "LIVE_API_KEY", "\"${liveApiKey...}\"")
buildConfigField("String", "LIVE_ENDPOINT", ...)
buildConfigField("String", "LIVE_MODEL", ...)
```

`defaultConfig` applies to **every** variant, so the key was baked into the release
variant's `BuildConfig` as a `public static final String`.

### Evidence that the key reached release bytecode

- `app/build/generated/source/buildConfig/release/com/anchor/mine/BuildConfig.java` (before fix):
  `public static final String LIVE_API_KEY = "NMpz…";` (redacted here; full value was present).
- `app/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes/com/anchor/mine/BuildConfig.class`
  (742 B) contained the key literal.

### Why the shipped APK *looked* clean — and why that was not a guarantee

Three scans of a freshly built `app-release.apk` found **zero** hits for the key,
`LIVE_API_KEY`, `api.mistral.ai`, and `mistral-small-latest`, and `mapping.txt` had no
`com.anchor.mine.BuildConfig` entry. The only reader of the field is
`app/src/androidTest/.../smoke/LiveSmoke15Test.kt`, which is androidTest-scoped and absent
from the release APK, so R8 eliminated the unreferenced field.

**Experiment that proved this was elimination, not absence** — a temporary release-reachable
reference in `MineApp.onCreate`:

| Variant of the reference | `key` hits in `classes.dex` | `api.mistral.ai` hits |
|---|---|---|
| `if (BuildConfig.LIVE_API_KEY.length > 1_000_000) Log.e(...)` | 0 | 0 |
| `Log.e("H1X", BuildConfig.LIVE_API_KEY + BuildConfig.LIVE_ENDPOINT)` | **1** | **1** |

The first form was folded away because the condition is provably false; the second kept the
field live and the key shipped **in cleartext**. Conclusion: the clean APK was an accident of
minification. Any future release-reachable reference to `BuildConfig.LIVE_API_KEY` would have
shipped the credential. This is a latent-injection defect, not a non-issue.

## Fix

Moved the three `buildConfigField` calls out of `defaultConfig` into `buildTypes { debug { … } }`
in `app/build.gradle.kts`. The release variant now has no `LIVE_*` fields at all.

## Verification

| Check | Command | Result |
|---|---|---|
| Release BuildConfig has no `LIVE_*` | `cat app/build/generated/source/buildConfig/release/.../BuildConfig.java` | Only `DEBUG/APPLICATION_ID/BUILD_TYPE/VERSION_CODE/VERSION_NAME` |
| Debug BuildConfig keeps them | `cat app/build/generated/source/buildConfig/debug/.../BuildConfig.java` | All three present, `// Field from build type: debug` |
| Release APK clean | `./gradlew :app:assembleRelease` then dex scan | `key=0 LIVE_API_KEY=0 LIVE_ENDPOINT=0 mistral=0` |
| `mapping.txt` | `grep com.anchor.mine.BuildConfig` | 0 entries |
| New guard test | `:app:testDebugUnitTest` → `TEST-com.anchor.mine.BuildConfigCredentialScopeTest.xml` | `tests=1 failures=0` |
| Full suite | `./gradlew :core:test :app:testDebugUnitTest` | core `tests=267 failures=0 errors=0`; app `tests=10 failures=0 errors=0` |

## Limitation of the test

`BuildConfigCredentialScopeTest` runs under `testDebugUnitTest`, i.e. against the debug
variant, so it can only assert the positive half (fields exist and carry values). The negative
half — that release has no fields — is enforced at build time by the variant scoping and
verified by the generated-source and dex-scan checks above. A JVM unit test cannot see the
release variant's `BuildConfig` in the same compilation unit.

## Residual risk

The key still exists in `local.properties` (git-ignored) and in every **debug** artifact
(`buildConfig/debug`, debug jars, debug APK). That is intended: `LiveSmoke15Test` needs it.
Do not distribute debug builds. If the key was ever committed or shared, rotate it.
