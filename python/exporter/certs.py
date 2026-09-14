"""
Make a frozen build able to verify Apple's ordinary public certificates.

Every request this exporter makes to Apple is TLS-verified by FindMy.py, against the platform's
trust store plus Apple's own pinned 2006 root. Two kinds of Apple host sit behind that, and they
fail apart:

- ``gsa.apple.com`` (sign-in) chains to the pinned root FindMy.py carries, so it verifies with no
  trust store at all.
- ``setup.icloud.com`` (the mobileme login, reached right after the verification code) presents an
  ordinary public certificate, which needs the platform's CA bundle like any other website.

A PyInstaller build carries no trust store, and its Python looks for one at the paths OpenSSL was
compiled with on the *build* machine, which do not exist on the user's. So sign-in succeeded and
then the very next request died with ``CERTIFICATE_VERIFY_FAILED: unable to get local issuer
certificate`` -- reported in
`#206 <https://github.com/parawanderer/OpenTagViewer/issues/206>`_ on a minimal Linux desktop,
where ``export SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt`` was the confirmed workaround.

:func:`ensure_ca_bundle` is that workaround, done for the user and portably. ``certifi`` ships
Mozilla's CA bundle *inside* the binary, and pointing OpenSSL's default search at it -- through the
same ``SSL_CERT_FILE`` variable OpenSSL reads when a context loads its default certificates -- fixes
every public Apple host at once. FindMy.py needs to know nothing: its
``ssl.create_default_context()`` reads that variable when it builds the context, which is why this
has to run before the first request, and why the entry points call it first thing.

It only fills a gap. A user who has set ``SSL_CERT_FILE`` themselves keeps it, and a machine whose
own default bundle exists -- every normal from-source run -- is left alone, so this changes nothing
outside the frozen-on-a-bare-system case it is for.
"""

from __future__ import annotations

import logging
import os
import ssl
from pathlib import Path

logger = logging.getLogger(__name__)


def ensure_ca_bundle() -> None:
    """Point OpenSSL at ``certifi``'s bundle when the platform offers no usable one."""
    if os.environ.get("SSL_CERT_FILE"):
        # The user, or a launcher, has already chosen a bundle. Theirs wins: it may point at a
        # corporate store this one would not contain.
        return

    default = ssl.get_default_verify_paths().cafile
    if default and Path(default).is_file():
        # OpenSSL's own default file is present, so the platform has a working trust store and
        # there is nothing to fix. This is every from-source run, and the case this must not
        # disturb -- a machine's store can carry CAs certifi's does not.
        return

    try:
        import certifi
    except ImportError:
        # Nothing to fall back to. Leave verification to fail loudly rather than papering over a
        # build that shipped without certifi.
        logger.warning("No usable system CA bundle and certifi is not installed; TLS may fail.")
        return

    bundle = certifi.where()
    if not bundle or not Path(bundle).is_file():
        logger.warning("certifi reported a CA bundle at %r, which is not a file.", bundle)
        return

    os.environ["SSL_CERT_FILE"] = bundle
    logger.info("No system CA bundle found; using certifi's at %s", bundle)
