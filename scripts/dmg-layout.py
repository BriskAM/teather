#!/usr/bin/env python3
"""Write Finder icon layout without driving Finder or requesting Accessibility."""
from pathlib import Path
import sys
from ds_store import DSStore
root = Path(sys.argv[1])
with DSStore.open(str(root / '.DS_Store'), 'w+') as store:
    store['.']['bwsp'] = {'ShowStatusBar': False, 'ShowToolbar': False, 'ShowPathbar': False, 'ShowSidebar': False, 'ContainerShowSidebar': False, 'WindowBounds': '{{200, 180}, {640, 400}}'}
    store['.']['icvp'] = {'viewOptionsVersion': 1, 'backgroundType': 1, 'backgroundColorRed': 0.965, 'backgroundColorGreen': 0.975, 'backgroundColorBlue': 0.94, 'gridOffsetX': 0.0, 'gridOffsetY': 0.0, 'gridSpacing': 100.0, 'arrangeBy': 'none', 'showIconPreview': False, 'showItemInfo': False, 'labelOnBottom': True, 'textSize': 14.0, 'iconSize': 112.0, 'scrollPositionX': 0.0, 'scrollPositionY': 0.0}
    store['.']['vSrn'] = ('long', 1)
    store['Teather.app']['Iloc'] = (170, 170)
    store['Applications']['Iloc'] = (470, 170)
