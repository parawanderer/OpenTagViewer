# Repo notes: OpenTagViewer

Apply these on top of the generic checklist when reviewing in `parawanderer/OpenTagViewer`
(or a fork of it). They exist because each one is something a real review pass - usually
Copilot's, occasionally a device - has already caught here.

## Conventions that decide where a fix lands

- **Stacked branches**: `feature/*` chains build on each other into a dev branch, each
  merged separately upstream. A bug belongs in the commit that introduced it; a fixup on
  top leaves a broken commit mid-stack.
- **Java/XML only** - no Kotlin, no Compose. Lombok `@Getter`/`@Builder`, RxJava3,
  Views + `RecyclerView` adapters.
- **Room is off-limits** for the nearby-scanner data (names, sighting logs) - files and
  SharedPreferences instead; the schema-migration rule makes any Room change expensive.
- **10 locales, machine-maintained**: new/changed UI strings go through
  `python3 scripts/add_strings.py <json>`; verify with `--check` (all locales covered).
  Never pass translations through shell quoting - mojibake history.
- **Verification**: `bash gradlew testAll` (JVM + pytest), `bash gradlew assembleDebug`,
  `python3 scripts/check_java_imports.py` (fully-qualified names in imports are
  *blocked* upstream - `pkg.Foo` in code must become a real import).
- The project's root `AGENTS.md` is normative: no silent fallbacks, no Room schema
  change without migration, verify library behaviour against the *pinned* version.

## Known Copilot findings on this repo (the bar to match)

- **PR #264**: a `ScanFilter` requiring the `0x12` type byte at a fixed offset, while
  the parser accepts TLV chains - real devices are silently filtered out. Reading the
  format spec once is not enough; check what the *parser* accepts vs what the *filter*
  requires.
- **PR #264**: a lifecycle path calling a subscribe method by hand - the framework
  re-enters on its own, leaking the first pair of Disposables.
- **PR #264**: an empty state reporting "no permission" when the real state was "none
  found" - honest-state finds count.
- **PR #266**: a deferred `getLastLocation` callback that did not re-check whether
  markers had arrived meanwhile - stale answers stomping newer decisions.
- **PR #266**: `addOnSuccessListener` without `addOnFailureListener` behind a
  decide-once flag - failure left the map at the provider default forever.
- **PR #266**: `Locale.getDefault().getCountry()` used where the app's locale config
  declares a bare `de` - no country component, so the intended region frame never
  matched.
- Text contrast 4.25:1 against the 4.5:1 floor - computed, not eyeballed.
