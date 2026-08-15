"""
Self-generated tags, from a file the user has to something a bundle can carry.

:mod:`opentagviewer_export.keyfiles` reads the file; this turns what it read into FindMy.py's own
`custom_rolling_key_accessory` mapping. The split is where the maths starts: parsing needs none,
and everything here derives a public key from a private one.

Two things that needs it for:

- **Checking a file that states its advertisement key.** Nothing can tell a private key from a
  public one by looking - both are 28 bytes - so a file that carries both is the only chance to
  catch the likeliest mistake, which is pasting the wrong one.
- **Naming a tag that arrived without a name.** Derived from the advertisement key, which is
  public by construction: the tag broadcasts it continuously, and it is what the Find My network
  is queried by. Nothing private reaches the name.
"""

from __future__ import annotations

from dataclasses import dataclass

from findmy import KeyPair
from findmy.accessory import FixedRollingKeyPairAccessory

from opentagviewer_export import CustomAccessoryExport, ParsedTag


class CustomTagError(Exception):
    """Raised when a parsed tag cannot be made into something exportable."""


@dataclass(frozen=True)
class PreparedTag:
    """A parsed tag with the questions answered that the file could not answer."""

    tag: ParsedTag
    name: str
    identifier: str

    def to_export(self) -> CustomAccessoryExport:
        """Render it as the mapping the bundle carries."""
        accessory = FixedRollingKeyPairAccessory(
            private_keys=list(self.tag.private_keys),
            name=self.name,
            identifier=self.identifier,
        )

        # FindMy.py's own serialisation rather than a second implementation of it: this is the
        # format whatever imports the bundle will read it back with.
        return CustomAccessoryExport(mapping=accessory.to_json())


def check_advertisement_key(tag: ParsedTag) -> None:
    """
    If the file stated an advertisement key, check the private key actually derives it.

    Does nothing when the file stated none, which is most of them.

    :raises CustomTagError: If the two disagree - which means the file's fields are swapped or it
        is not the file it appears to be. Worth stopping for: the export would otherwise succeed
        and produce a tag that never appears, with nothing to explain why.
    """
    if tag.advertisement_key is None:
        return

    derived = {KeyPair(private_key=key).adv_key_bytes for key in tag.private_keys}

    if tag.advertisement_key not in derived:
        raise CustomTagError(
            "That file's private key does not produce the advertisement key it also carries."
            " Either the two fields are the wrong way round, or they came from different tags."
            " Exporting it would produce a tag that never appears on the map.",
        )


def suggested_name(tag: ParsedTag) -> str:
    """
    A name for a tag whose file did not carry one.

    From the advertisement key, which is public - the tag broadcasts it, and it is what the
    network is queried by - so this leaks nothing that holding the tag near a phone would not.
    Short enough to type, and stable, so the same tag suggests the same name twice.
    """
    if tag.name:
        return tag.name

    # The last key is the current one, in every format read here.
    derived = KeyPair(private_key=tag.private_keys[-1]).adv_key_bytes

    return f"Tag {derived[:3].hex().upper()}"


def suggested_identifier(tag: ParsedTag) -> str:
    """
    An identifier for a tag whose file did not carry one, on the same terms as the name.

    It becomes the tag's filename inside the bundle, so it is the advertisement key's first bytes
    rather than anything the user typed: two tags a user happens to name the same thing must not
    collide, and a name is exactly the thing people reuse.
    """
    if tag.identifier:
        return tag.identifier

    derived = KeyPair(private_key=tag.private_keys[-1]).adv_key_bytes

    return f"tag-{derived[:6].hex()}"
