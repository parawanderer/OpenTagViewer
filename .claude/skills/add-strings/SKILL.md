---
name: add-strings
description: Add or back-fill user-facing Android strings across all locales in OpenTagViewer. Use whenever adding, renaming or translating any string that appears in the UI, or when checking translations are complete before a PR.
---

# Adding user-facing strings

The app ships ten locales. A string missing from one silently falls back to English — nothing
fails, and it looks correct in whichever language you happen to read. So every UI string has
to land in every locale in one go.

## The rule that matters

**Never pass translations through a shell argument.** Write a JSON file with the Write tool,
then point the script at it. Passing non-ASCII text through `bash -c`, `sed` or a here-doc has
corrupted it twice in this repo — a French apostrophe reached the screen as a literal
`\&#8217;`. The Write tool does not go through shell quoting; a `for` loop does.

For the same reason, do not hand-edit ten `strings.xml` files with ten Edit calls unless the
script cannot do what you need. It is slower and it is what the script exists to replace.

## Workflow

1. Find out which locales are required — do not assume, they are discovered from the tree:

   ```bash
   python scripts/add_strings.py --locales
   ```

2. Write the JSON with the **Write** tool, one entry per string, every locale present:

   ```json
   {
     "how_do_i_get_the_zip": {
       "default": "How do I get the zip?",
       "en": "How do I get the zip?",
       "de": "Wie erhalte ich die ZIP-Datei?",
       "fr": "Comment obtenir l’archive zip ?",
       "nl": "Hoe kom ik aan de zip?",
       "ru": "Как получить zip-архив?",
       "ja": "zip ファイルの入手方法",
       "ko": "zip 파일은 어떻게 받나요?",
       "zh-rCN": "如何获取 zip 文件？",
       "zh-rTW": "如何取得 zip 檔案？"
     }
   }
   ```

   Put it in the scratchpad or `tmp/`, not in the repo root.

3. Apply, then confirm nothing drifted:

   ```bash
   python scripts/add_strings.py tmp/new_strings.json
   python scripts/add_strings.py --check
   ```

4. Build. `aapt` is the real judge of whether the XML is acceptable:

   ```bash
   JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' ./gradlew.bat :app:assembleDebug
   ```

## Flags

| Command | What it does |
| --- | --- |
| `add_strings.py` (no args) | Prints full usage and the input format |
| `--locales` | Lists discovered locales and their files |
| `<file.json>` | Adds strings; **errors** if any locale is missing or the name already exists |
| `--fill <file.json>` | Adds only where missing — for back-filling what `--check` reported |
| `--check` | Fails if any locale lacks a string the default locale has. Run before a PR |

## Translating

Translate properly rather than leaving English in place; the existing files are fully
translated and a stray English string is conspicuous. Points that have come up here:

- French uses a space before `:`, `?` and `!`, and reads better with `’` than `'`
- CJK wants the full-width colon `：` and, for a gap, the ideographic space `　`
- Keep positional format specifiers (`%1$d`, `%2$s`) intact and in a natural order for the
  language — they may be reordered, which is the point of the positional form
- Product names (`Google Maps`, `AMap`, `Anisette`) generally stay as they are

## What the script already handles

Do not do these by hand:

- Escaping `'`, `"` and `&` as Android requires
- Preserving `<u>`, `<b>`, `<i>` so emphasis survives — styling that belongs to the copy
  belongs in the resource, not in a paint flag in code that would only ever apply to English
- Re-parsing each file after writing, so a bad write fails immediately instead of at aapt time
- `translatable="false"` — set `"translatable": false` and supply only `"default"`

## Related

`AGENTS.md` documents this generically for any agent. Keep the two in step if the tool changes.
