---
name: investigating-bug-reports
description: Read a GitHub bug report completely - every comment and every image - before diagnosing or asking the reporter for more. Use whenever triaging an issue here, answering a reporter, or deciding a report lacks detail.
---

# Reading a bug report before answering it

**The failure this exists for is concluding "there is not enough here" while the answer is in the
thread.** It is not a slower diagnosis; it is asking somebody to send a thing they already sent,
then reasoning from a guess while waiting for it.

Two things get missed, and both are invisible in the obvious `gh` call.

## 1. Pull the whole thread, not the body

`gh issue view <n>` prints the body. The diagnosis is often three comments down — a reporter who
answered a question, a second person with the same symptom on different hardware, or the reporter
correcting their own title.

```bash
gh issue view <n> --json number,title,author,createdAt,state,labels,body,comments \
  --jq '"#\(.number) \(.title)\nby \(.author.login) at \(.createdAt)  [\(.state)]\n\n\(.body)\n\n--- COMMENTS ---\n"
        + ([.comments[] | "[\(.author.login) \(.createdAt)]\n\(.body)"] | join("\n\n"))'
```

**`gh issue view <n> --comments` has come back empty here on an issue that has comments**, which is
why the `--json` form is the one written down. A blank result from the convenience flag is not
evidence the thread is empty — check with the JSON before believing it.

## 2. Read the images. This is the one that gets skipped

**This is a phone app, so its errors arrive on a screen, so that is how they get reported.** The
template's log field and "what happened" field are routinely empty while a screenshot carries the
whole thing — pasting a screenshot is one gesture, capturing a log is a menu, a file and a decision
about what to redact.

`gh` gives you the URL, not the picture, and an `<img>` tag sitting in a body reads like decoration
next to prose. **A report whose fields are blank is not a report with nothing in it. It is one
where everything is in the attachment.**

Then download each one and **Read** it. The `Read` tool renders an image, but only from a local
path — there is no fetching an image straight from a URL:

```bash
curl -sL -o "$SCRATCH/shot1.png" "https://github.com/user-attachments/assets/<id>"
file "$SCRATCH/shot1.png"    # confirm it is a PNG and not an HTML error page
```

Older reports use `https://user-images.githubusercontent.com/...` instead; same treatment.

A private repository's attachments need auth — `gh api` with the URL, or a cookie. This repository
is public, so plain `curl -sL` is enough, and `file` saying `PNG image data` is the check that it
worked.

## 3. Know which post each image belongs to, and what it is answering

**A bare list of URLs strips the context that makes a screenshot mean anything.** Three images in a
thread are usually not three views of one bug: one is the original symptom, one is a reporter
answering "what does Settings say", and one is somebody else's different problem. Read as an
undifferentiated pile, they contradict each other, and the contradiction looks like an
unreproducible bug.

So attribute each one before opening it — who posted it, in which post, and what they were saying
when they attached it. This prints exactly that, with the text leading up to each image:

```bash
gh issue view <n> --json body,comments,author,createdAt --jq '
  ([{who: .author.login, where: "body", text: .body}]
   + [.comments[] | {who: .author.login, where: "comment", text: .body}])
  | to_entries[] | .key as $i | .value as $p
  | ($p.text | [scan("https://github.com/user-attachments/assets/[A-Za-z0-9-]+")])
  | select(length > 0) | .[] | . as $url
  | ($p.text | split($url) | .[0] | .[-220:] | gsub("\n"; " ⏎ ")) as $before
  | "=== \($p.where)#\($i) by \($p.who)\n\($url)\ncontext before: …\($before)\n"'
```

Then carry that label with the image when you reason about it: *"the screenshot in the body, under
'What happened — tried to login using another account separated from my main account'"* — not
*"the screenshot"*. The label is what tells you the account in the picture is a **secondary**
account, which on #221 was half the diagnosis.

## 4. Only then reason about the cause

Not before. With every post read and every image seen, ask what the evidence supports — and let it
overrule the title, the label, and whatever the code's own error message asserts.

On #221 all three were wrong in the same direction. The app classified the failure as *terms of
service* and said so on screen; the screenshot showed `localizedError` absent and the delegate's
own `status=1` present, which is a different channel of the same response and not terms at all.
**The error message a program prints is a hypothesis its author wrote in advance, not evidence.**
Evidence is what the server actually returned.

Then say which parts are established and which are inference, the same way rule 2 asks. "Apple
returned this string; the neighbours diagnose that string as X; I have not reproduced it" is a
useful answer. "It is X" is not, when it is not.

## Only then decide whether detail is missing

Say what the screenshot showed. If something genuinely is absent, ask for that one thing, and do
not ask for anything the thread already contains.

**Quote the error text, never the surrounding screen, and never re-upload the image.** Reporters
redact unevenly: one here blacked out a phone number by hand and left the pixels either side of it
untouched. A screenshot is personal data that happens to contain a stack trace.

## Worth doing at the same time

- **Check the neighbours** (`AGENTS.md` rule 18). This project shares its authentication path with
  AltStore, SideStore, Macless Haystack and OpenBubbles, and the same Apple-side message is usually
  already diagnosed on one of their trackers.
- **Check the fork** (rule 19): `gh pr list --repo parawanderer/FindMy.py`.
- **Ask whether it is already reported**: `gh issue list --state all --limit 100`.

## Where this came from

[#221](https://github.com/parawanderer/OpenTagViewer/issues/221). The log field was empty and a
reply was drafted asking the reporter to capture one. The attached screenshot already held the
complete error — the exception type, `status=1`, and the `status-message` Apple sent — and that
string named a cause that was neither of the two being guessed at. Nothing was missing from the
report except somebody looking at it.

## Related

- `AGENTS.md` rule 20 — the short version of this, for agents who never load a skill.
- `AGENTS.md` rule 17 — how to write the reply once you know the answer. The register in `AGENTS.md`
  is for agents; a reporter gets the finding and the remedy.
