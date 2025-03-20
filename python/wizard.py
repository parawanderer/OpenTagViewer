import os
import datetime
import tempfile
import time
import yaml
import shlex
from zipfile import ZipFile
import tkinter as tk
from tkinter import NSEW, ttk
from tkinter.filedialog import asksaveasfilename

from tkinter import messagebox

from airtag_decryptor import (
    KEYCHAIN_LABEL,
    INPUT_PATH,
    WHITELISTED_DIRS,
    get_key,
    decrypt_plist,
    make_output_path,
    dump_plist
)

# Wrapper around the main decryptor implementation that allows to filter which beacon files get exported/zipped


APP_TITLE = "OpenTagViewer AirTag Exporter"
DROPDOWN_DESCRIPTION = "Choose devices to export:"
GITHUB_ISSUES_LINK = "https://github.com/parawanderer/OpenTagViewer/issues"

VERSION = "1.0.0"

EXPORT_METADATA_FILENAME = "OPENTAGVIEWER.yml"
EXPORT_METADATA_VERSION = "0.0.1"
EXPORT_METADATA_VIA_NAME = f"OpenTagViewer.app:{VERSION}"


class PListFileInfo:
    def __init__(self, filepath: str, data: dict):
        self.filepath = filepath
        self.data = data


class BeaconData:
    def __init__(
            self,
            owned_beacon: PListFileInfo,
            beacon_naming_record: PListFileInfo = None,
            beacon_name: str = None,
            beacon_emoji: str = None):
        self.owned_beacon = owned_beacon
        self.beacon_naming_record = beacon_naming_record
        self.beacon_name = beacon_name
        self.beacon_emoji = beacon_emoji


class WizardApp(tk.Tk):
    def __init__(self, *args, **kwargs):
        tk.Tk.__init__(self, *args, **kwargs)

        self.title(APP_TITLE)
        self.geometry("480x267")
        self.minsize(480, 267)
        self.maxsize(720, 400)


        self.container = ttk.Frame(self)
        self.container.pack(side="top", fill="both", expand=True)
        self.container.grid_rowconfigure(0, weight=1)
        self.container.grid_columnconfigure(0, weight=1)

        self.label = tk.Label(self.container, text=DROPDOWN_DESCRIPTION, font=("Arial", 12))
        self.label.grid(row=0, column=0, columnspan=3, sticky=NSEW, pady=2)

        self.beacon_data: dict[str, BeaconData] = self._create_beacon_data_map()

        self.choices_select = tk.Listbox(
            self.container,
            selectmode="multiple"
        )

        self.options = [f"{"" if b.beacon_emoji is None else b.beacon_emoji + " "}{b.beacon_name} - {bid}" for bid, b in self.beacon_data.items()]
        self.options.sort()

        self.choices_select.insert(1, *self.options)
        self.choices_select.grid(row=1, column=0, columnspan=3, rowspan=3, sticky=NSEW, pady=2)

        self.cancel_button = tk.Button(self.container, text="Cancel", command=self.handle_cancel)
        self.cancel_button.grid(row=4, column=1, padx=10, pady=2)
        self.confirm_button = tk.Button(self.container, text="Confirm", command=self.handle_confirm)
        self.confirm_button.grid(row=4, column=2, padx=10, pady=2)


    def _create_beacon_data_map(self) -> dict[str, BeaconData]:
        # get key: prompts password entry (for some reason twice)
        beacon_store_key: bytearray = get_key(KEYCHAIN_LABEL)

        # get needed files
        owned_beacons: list[PListFileInfo]
        beacon_naming_records: list[PListFileInfo]
        for path, folders, _ in os.walk(INPUT_PATH):
            for foldername in folders:
                if foldername not in WHITELISTED_DIRS:
                    continue

                plists = self._extract_plists(
                    path,
                    foldername,
                    beacon_store_key
                )

                if foldername == "OwnedBeacons":
                    owned_beacons = plists
                elif foldername == "BeaconNamingRecord":
                    beacon_naming_records = plists
            break

        # map them by beaconId
        m: dict[str, BeaconData] = {}

        # map OwnedBeacons
        for owned_beacon in owned_beacons:
            beacon_id: str = owned_beacon.data["identifier"]
            m[beacon_id] = BeaconData(owned_beacon, None)

        # join them with their BeaconNamingRecords
        for beacon_naming_record in beacon_naming_records:
            beacon_id: str = beacon_naming_record.data["associatedBeacon"]
            if beacon_id not in m:  # this case shouldn't really happen
                continue

            m[beacon_id].beacon_naming_record = beacon_naming_record
            m[beacon_id].beacon_name = beacon_naming_record.data.get("name", "")
            m[beacon_id].beacon_emoji = beacon_naming_record.data.get("emoji", None)


        # cleanup any bad ones:
        for beacon_id in m.keys():
            if m[beacon_id].beacon_naming_record is None:
                # shouldn't happen, but clean up just in case
                del m[beacon_id]

        return m


    def _extract_plists(self, input_base_path: str, subfolder: str, key: bytearray) -> list[PListFileInfo]:
        search_path: str = os.path.join(input_base_path, subfolder)

        res: list[PListFileInfo] = []

        for path, folders, files in os.walk(search_path):
            for filename in files:
                try:
                    file_fullpath: str = os.path.join(path, filename)
                    plist: dict = decrypt_plist(file_fullpath, key)
                    res.append(PListFileInfo(file_fullpath, plist))
                except Exception as e:
                    messagebox.showwarning(
                        "Error Decrypting Airtag Data",
                        f"A non-fatal error occurred when trying to decrypt plist file '{file_fullpath}'. Report this bug on Github: {GITHUB_ISSUES_LINK}"
                    )

        return res


    def handle_confirm(self):
        current_selection = self.choices_select.curselection()
        print(f"Current selection on clicking confirm was: {current_selection}")
        if len(current_selection) == 0:
            return # no items selected = nothing to do

        # get which ones:
        beacon_ids: list[str] = []
        for i in current_selection:
            opt: str = self.options[i]
            beacon_id = opt[-36:]
            beacon_ids.append(beacon_id)


        initial_filename = f"OpenTagViewer_export_{datetime.datetime.now().strftime('%S%M%H%d%m%Y')}.zip"
        save_filename: str|None = asksaveasfilename(
            initialfile=initial_filename,
            defaultextension=".zip",
            filetypes=[("Zip Archives", "*.zip")]
        )

        print(f"Filename choice was: {save_filename}")
        if save_filename is None:
            return # cancelled

        with tempfile.TemporaryDirectory() as tmpdirname:

            # dump plist files to tmpdir
            for beacon_id in beacon_ids:
                beacon = self.beacon_data[beacon_id]

                output_file1: str = make_output_path(
                    tmpdirname,
                    beacon.beacon_naming_record.filepath,
                    INPUT_PATH
                )
                print(f"Now dumping '{beacon.beacon_naming_record.filepath}' to {output_file1}...")
                dump_plist(beacon.beacon_naming_record.data, output_file1)

                output_file2: str = make_output_path(
                    tmpdirname,
                    beacon.owned_beacon.filepath,
                    INPUT_PATH
                )
                print(f"Now dumping '{beacon.owned_beacon.filepath}' to {output_file2}...")
                dump_plist(beacon.owned_beacon.data, output_file2)

            # We need tomake an export metadata file in the root dir...
            export_metadata = {
                "version": EXPORT_METADATA_VERSION,
                "exportTimestamp": int(time.time() * 1000),
                "sourceUser": os.getenv("USER"),
                "via": EXPORT_METADATA_VIA_NAME
            }

            metadata_file = os.path.join(tmpdirname, EXPORT_METADATA_FILENAME)
            with open(metadata_file, 'w') as fmeta:
                yaml.dump(export_metadata, fmeta, default_flow_style=False)
                print(f"Created metadata file: {metadata_file}")


            # zip to desired target file:
            print(f"Now writing compressed zip file to '{save_filename}'...")
            with ZipFile(save_filename, 'w') as zip_obj:
                for folder, subfolders, filenames in os.walk(tmpdirname):
                    for filename in filenames:
                        filename_path = os.path.join(folder, filename)
                        print(f"Now zipping '{filename_path}'")
                        zip_obj.write(filename_path, os.path.relpath(filename_path, tmpdirname))

            if os.path.exists(save_filename):
                print(f"Successfully created zip at '{save_filename}'")
                os.system(f"open {shlex.quote(os.path.dirname(save_filename))}")
            else:
                messagebox.showerror("Export Error", f"Failed to create export zip at {save_filename}!")

        print("Bye!")
        self.quit()


    def handle_cancel(self):
        self.quit()


if __name__ == "__main__":
    app = WizardApp()
    app.mainloop()