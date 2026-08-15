"""
Rendering Apple's terms of service as readable text.

What is displayed is what gets agreed to, so the tests that matter are the ones about *not losing
anything*: every paragraph survives, nothing is reordered, and no markup reaches the reader. The
prettiness is secondary and only lightly checked.
"""

from __future__ import annotations

from exporter.terms import render, summarise, terminal_width

# The shape Apple's BuddyML pages actually take: a wrapper div, headings, paragraphs, a list, and
# markup that has to disappear rather than be shown.
TERMS_HTML = """
<html><head><style>p { color: red }</style></head>
<body>
  <div class="terms">
    <h1>iCloud Terms and Conditions</h1>
    <p>Welcome to <b>iCloud</b>. By using iCloud you agree to these terms.</p>
    <h2>1. Your Account</h2>
    <p>You are responsible for maintaining the confidentiality of your account.</p>
    <ul>
      <li>You must be over the age of majority.</li>
      <li>You may not share your account.</li>
    </ul>
    <script>track();</script>
  </div>
</body></html>
"""


class TestNothingIsLost:
    def test_every_paragraph_survives(self):
        text = render(TERMS_HTML, width=80)

        assert "By using iCloud you agree to these terms." in text
        assert "responsible for maintaining the confidentiality" in text

    def test_every_list_item_survives(self):
        text = render(TERMS_HTML, width=80)

        assert "over the age of majority" in text
        assert "may not share your account" in text

    def test_the_order_is_the_documents_order(self):
        text = render(TERMS_HTML, width=80)

        assert text.index("Welcome to iCloud") < text.index("Your Account".upper())
        assert text.index("YOUR ACCOUNT") < text.index("over the age of majority")

    def test_nothing_is_shown_twice(self):
        # A wrapping <div> holds every paragraph inside it, so rendering both the wrapper and its
        # children would show the whole document twice - once flat and once formatted.
        assert render(TERMS_HTML, width=80).count("age of majority") == 1


class TestNothingSpuriousIsShown:
    def test_no_markup_reaches_the_reader(self):
        text = render(TERMS_HTML, width=80)

        assert "<b>" not in text
        assert "<p>" not in text
        assert "class=" not in text

    def test_scripts_and_styles_are_not_read_out_as_text(self):
        text = render(TERMS_HTML, width=80)

        assert "track()" not in text
        assert "color: red" not in text

    def test_inline_emphasis_keeps_its_words(self):
        # <b>iCloud</b> is a word in a sentence, not decoration to drop with the tag.
        assert "Welcome to iCloud." in render(TERMS_HTML, width=80)


class TestItReadsAsADocument:
    def test_headings_are_underlined_so_they_read_as_headings(self):
        lines = render(TERMS_HTML, width=80).splitlines()
        heading = lines.index("ICLOUD TERMS AND CONDITIONS")

        assert set(lines[heading + 1]) == {"-"}

    def test_list_items_are_bulleted_and_hang(self):
        assert "  - You must be over the age of majority." in render(TERMS_HTML, width=80)

    def test_paragraphs_are_wrapped_to_the_width_given(self):
        long_html = "<p>" + ("word " * 200) + "</p>"

        assert all(len(line) <= 40 for line in render(long_html, width=40).splitlines())

    def test_blank_lines_separate_blocks(self):
        assert "\n\n" in render(TERMS_HTML, width=80)

    def test_a_line_break_in_the_document_is_a_line_break_here(self):
        # An address is the case that shows it: collapsing whitespace across the whole block runs
        # three lines into one, which looks like a transcription error in a contract.
        address = "<p>Apple Inc.<br>One Apple Park Way<br>Cupertino, CA 95014</p>"

        assert render(address, width=80) == "Apple Inc.\nOne Apple Park Way\nCupertino, CA 95014"


class TestAwkwardDocuments:
    def test_a_document_with_no_recognisable_blocks_is_still_shown(self):
        # Swallowing it would leave someone agreeing to a blank screen.
        assert "some bare text" in render("some bare text", width=80)

    def test_an_empty_document_renders_to_nothing_rather_than_failing(self):
        assert render("", width=80) == ""

    def test_a_table_keeps_its_cells(self):
        table = "<table><tr><td>Region</td><td>Terms apply</td></tr></table>"

        rendered = render(table, width=80)

        assert "Region" in rendered
        assert "Terms apply" in rendered


class TestSummarising:
    def test_names_the_document_and_says_how_long_it_is(self):
        # Shown before the pager opens, so someone knows whether this is two pages or forty.
        summary = summarise("iCloud", TERMS_HTML)

        assert summary.startswith("iCloud (")
        assert "words" in summary


class TestWidth:
    def test_stays_readable_whatever_the_terminal_says(self):
        assert 40 <= terminal_width() <= 88
