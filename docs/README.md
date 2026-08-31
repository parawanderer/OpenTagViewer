# docs

Longer-form documents that are neither rules nor setup instructions.

The rules for changing this project live in [AGENTS.md](../AGENTS.md); how to build and test it
lives in [CONTRIBUTING.md](../CONTRIBUTING.md). What lands here is the other thing: protocol
specifications and investigation records — the material that is expensive to rediscover and has
no natural home in a source comment.

| Document | What it is |
| --- | --- |
| [how-to-export-with-the-cli.md](./how-to-export-with-the-cli.md) | How to export AirTags out of iCloud with no Mac. Written for whoever is running it rather than for whoever is changing it, which is why it is the one page here addressed to a user. |
| [android-import-handover.md](./android-import-handover.md) | What the Android app has to do to catch up with the exporter: the shared package's Chaquopy wiring, encrypted bundles, self-generated tags. A task brief - delete it when the work lands. |
| [anisette-native-android.md](./anisette-native-android.md) | Proof-of-concept log for running Apple's ADI libraries in-process on Android, and the measurements that came out of it. Historical; the implementation shipped. |
| [findmy-export/](./findmy-export/) | Clean-room specification of the protocol for reading FindMy accessory keys from iCloud without a Mac. Stage 1 written; five stages to go. |
| [owner-connected-tags.md](./owner-connected-tags.md) | Reading list for the one case the app cannot handle: matching or ringing a tag while it is with its owner, so the Find My network never sees it. Links plus what each one actually establishes — including that authorised and unauthorised ringing are different protocols. |

## If you add a document here

Add a row above in the same commit. A list that nothing checks goes stale silently, which is
the failure mode [AGENTS.md rule 10](../AGENTS.md) exists to prevent.

Records of investigations are worth keeping even when they describe work that has since
shipped, provided they say so at the top. What makes them worth the disk is the measurements and
the dead ends — the things a reader would otherwise pay for twice.
