package dev.wander.android.opentagviewer.python;

/**
 * What an accessory is, in words, according to the shared heuristic.
 *
 * <p><b>The heuristic itself is not here, and must not be copied here.</b> It lives in
 * {@code opentagviewer_export/hardware.py}, where the desktop exporter also uses it when it asks
 * which accessories to export. It is guesswork over half a dozen fields - product and vendor ids,
 * the model, the shape of {@code stableIdentifier} - and the vendor list came out of the
 * Bluetooth SIG registry, so it grows as accessories turn up. Two copies means two things to
 * update and one of them will be forgotten; the symptom of that is one tag described as an AirTag
 * on one screen and as a hex number on the other.
 *
 * <p>Behind an interface because the real one is Chaquopy: it starts the interpreter, imports the
 * package and parses a plist. A screen that called it directly could not be launched in a test
 * without all of that working, and "an accessory nothing recognises" is a state worth being able
 * to render on demand rather than by finding such a tag.
 *
 * <p><b>Null is a real answer, not a failure.</b> It means nothing recognised the record, and the
 * caller should then show what it already knows rather than a guess - the costs are asymmetric,
 * because a wrong name is believed and a hex number gets looked up.
 */
public interface HardwareDescriber {

    /**
     * A human-readable name for the accessory, or null if nothing recognises it.
     *
     * @param plistXml the {@code OwnedBeacons} plist, as the app stores it. Null for a tag that
     *                 never had one - a self-generated tag - which returns null rather than
     *                 throwing, because that kind already describes itself.
     */
    String describe(String plistXml);

    /**
     * One line on how the user could find out what an unrecognised accessory is, or null when
     * there is nothing worth saying - which is the common case.
     */
    String whereToLookUp(String plistXml);
}
