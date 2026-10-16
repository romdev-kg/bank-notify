#!/usr/bin/env python3
"""Give AGP outputs stable, versioned download names."""
import json
import pathlib
import shutil
import sys

variant = sys.argv[1]
output = pathlib.Path('app/build/outputs/apk') / variant
metadata = json.loads((output / 'output-metadata.json').read_text())
pathlib.Path('dist').mkdir(exist_ok=True)
for element in metadata['elements']:
    version = element['versionName']
    shutil.copy2(output / element['outputFile'], f'dist/BankNotify-{version}-{variant}.apk')
