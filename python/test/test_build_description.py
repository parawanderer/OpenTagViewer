"""
Saying which exporter wrote a log.

**A log that arrives attached to a bug report used to say nothing about what produced it**, so
`.github/ISSUE_TEMPLATE/exporter-bug.yml` has to ask - and the answer somebody gives is the
version they remember. On a checkout that is whatever the last release set, confidently and
wrongly, because `VERSION` is a committed literal and every commit after a release carries it.

The same gap the Android app has with `Import.via`: the value is known, and nothing says it.
"""

from __future__ import annotations

import logging
import subprocess

from exporter import cli, version


class TestWhatItReports:
    def test_a_frozen_build_is_just_the_version(self, monkeypatch):
        # What people download. There is no checkout to ask, and `VERSION` is exactly right.
        monkeypatch.setattr(version.sys, "frozen", True, raising=False)

        assert version.describe_build() == version.VERSION

    def test_a_frozen_build_does_not_go_looking_for_git(self, monkeypatch):
        # Not merely wasteful: `git` in a frozen app's directory is somebody else's repository,
        # so an answer from it would be worse than no answer.
        monkeypatch.setattr(version.sys, "frozen", True, raising=False)
        monkeypatch.setattr(version, "_commit", _never_called)

        version.describe_build()

    def test_a_checkout_names_the_commit(self, monkeypatch):
        monkeypatch.setattr(version, "_commit", lambda: "abc1234")

        described = version.describe_build()

        assert version.VERSION in described, "the version is still a useful hint"
        assert "abc1234" in described, "and the commit is the part that identifies the build"

    def test_no_git_falls_back_to_the_version(self, monkeypatch):
        # A source zip off a release tag, most likely - where VERSION is right again.
        monkeypatch.setattr(version, "_commit", lambda: None)

        assert version.describe_build() == version.VERSION


class TestItCannotBreakARun:
    """
    This runs while logging is being set up, before anything the user asked for has started.

    Every way it can fail has to be a missing suffix rather than a failed export.
    """

    def test_no_git_on_path(self, monkeypatch):
        monkeypatch.setattr(subprocess, "run", _raise(FileNotFoundError("git")))

        assert version._commit() is None

    def test_a_git_that_hangs(self, monkeypatch):
        monkeypatch.setattr(subprocess, "run", _raise(subprocess.TimeoutExpired("git", 2)))

        assert version._commit() is None

    def test_not_a_repository(self, monkeypatch):
        monkeypatch.setattr(subprocess, "run", _returning(128, ""))

        assert version._commit() is None

    def test_an_empty_answer(self, monkeypatch):
        monkeypatch.setattr(subprocess, "run", _returning(0, "\n"))

        assert version._commit() is None

    def test_it_is_asked_of_this_checkout(self, monkeypatch):
        # Not of the working directory, which is wherever the user happened to be standing.
        seen = {}

        def _record(_args, **kwargs):
            seen.update(kwargs)
            return _Finished(0, "abc1234")

        monkeypatch.setattr(subprocess, "run", _record)
        version._commit()

        assert seen["cwd"].name == "exporter"
        assert seen["timeout"], "an unbounded git call can hang a run before it starts"


class TestItReachesTheLog:
    def test_the_cli_says_it_when_logging_is_turned_up(self, monkeypatch, caplog):
        monkeypatch.setattr(version, "_commit", lambda: "abc1234")

        with caplog.at_level(logging.INFO):
            cli.configure_logging(2)

        assert any("abc1234" in r.getMessage() for r in caplog.records), caplog.text


class _Finished:
    def __init__(self, returncode, stdout):
        self.returncode = returncode
        self.stdout = stdout


def _raise(error):
    def _boom(*_args, **_kwargs):
        raise error

    return _boom


def _returning(returncode, stdout):
    return lambda *_args, **_kwargs: _Finished(returncode, stdout)


def _never_called():
    raise AssertionError("a frozen build must not shell out to git")
