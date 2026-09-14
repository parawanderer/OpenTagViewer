"""
Tests for `exporter.certs`, which fills the CA gap a frozen build has on a bare system.

The behaviour that matters is when it acts and when it keeps its hands off: a user's own
`SSL_CERT_FILE` and a machine with a working store must both be left exactly as they were, or a
fix for #206 becomes a regression for everyone the bug never touched.
"""

from __future__ import annotations

import ssl
from pathlib import Path

import pytest

from exporter import certs


@pytest.fixture(autouse=True)
def _clean_env(monkeypatch):
    monkeypatch.delenv("SSL_CERT_FILE", raising=False)


def _no_default(monkeypatch):
    """Pretend OpenSSL's compiled default file does not exist -- the frozen-build case."""
    paths = ssl.DefaultVerifyPaths("/nonexistent/cert.pem", "", "", "/nonexistent", "", "")
    monkeypatch.setattr(ssl, "get_default_verify_paths", lambda: paths)


def _has_default(monkeypatch, tmp_path):
    """A present default file -- a normal from-source run."""
    real = tmp_path / "ca-certificates.crt"
    real.write_text("-----BEGIN CERTIFICATE-----\n")
    paths = ssl.DefaultVerifyPaths(str(real), str(real), "", "/etc/ssl/certs", "", "")
    monkeypatch.setattr(ssl, "get_default_verify_paths", lambda: paths)


def test_a_users_own_setting_is_left_untouched(monkeypatch):
    _no_default(monkeypatch)
    monkeypatch.setenv("SSL_CERT_FILE", "/home/someone/corporate-ca.pem")

    certs.ensure_ca_bundle()

    import os

    assert os.environ["SSL_CERT_FILE"] == "/home/someone/corporate-ca.pem"


def test_a_working_system_store_is_left_alone(monkeypatch, tmp_path):
    _has_default(monkeypatch, tmp_path)

    certs.ensure_ca_bundle()

    import os

    assert "SSL_CERT_FILE" not in os.environ, "must not override a machine that already verifies"


def test_certifi_fills_the_gap_when_nothing_else_will(monkeypatch):
    _no_default(monkeypatch)

    certs.ensure_ca_bundle()

    import os

    bundle = os.environ.get("SSL_CERT_FILE")
    assert bundle is not None, "a bare frozen build must be given a bundle"
    assert Path(bundle).is_file()
    import certifi

    assert bundle == certifi.where()


def test_nothing_is_set_when_certifi_is_absent(monkeypatch):
    _no_default(monkeypatch)
    import builtins

    real_import = builtins.__import__

    def deny_certifi(name, *args, **kwargs):
        if name == "certifi":
            raise ImportError("no certifi")
        return real_import(name, *args, **kwargs)

    monkeypatch.setattr(builtins, "__import__", deny_certifi)

    certs.ensure_ca_bundle()

    import os

    assert "SSL_CERT_FILE" not in os.environ
