# Python Utility Scripts


## airtag-decryptor.py

This is an implementation based on [airtag-decryptor.swift](https://gist.github.com/airy10/5205dc851fbd0715fcd7a5cdde25e7c8)
by [airty10](https://gist.github.com/airy10), which was based on [airtag-decryptor.swift by Matus](https://gist.github.com/YeapGuy/f473de53c2a4e8978bc63217359ca1e4),
but in python.

Reasoning for this: I don't know Swift (and I don't even use MacOS) and I need to copy the logic into an UI app for MacOS for my android OpenTagViewer app.

### How to use:

- You need [python3 and pip](https://packaging.python.org/en/latest/tutorials/installing-packages/)
- Install requirements:
    ```bash
    pip install -r requirements.txt
    ```
- **(OPTIONAL)** Change your output path on [airtag-decryptor.py:30](https://github.com/parawanderer/OpenTagViewer/blob/main/scripts/airtag-decryptor.py#L30) if wanted
    - Default output path: `~/plist_decrypt_output`
- Run the script:
    ```bash
    python airtag-decryptor.py
    ```
    - Note that it will prompt your password twice.
- The script will open the specified output folder on success