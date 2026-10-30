#!/usr/bin/env python3
"""Capture and smoke-test real emulator screens using UIAutomator's visible tree."""
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
root = Path(__file__).resolve().parents[1]
adb = str(Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk'))) / 'platform-tools/adb')
output = root / 'docs/screenshots'
output.mkdir(parents=True, exist_ok=True)
def run(*args): return subprocess.run([adb, '-e', *args], check=True, capture_output=True).stdout
def tree():
    run('shell', 'uiautomator', 'dump', '/sdcard/kunnect-ui.xml')
    return ET.fromstring(run('shell', 'cat', '/sdcard/kunnect-ui.xml'))
def tap(label):
    node = next(n for n in tree().iter('node') if n.get('text') == label)
    x1,y1,x2,y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    run('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
def capture(name, required):
    nodes = list(tree().iter('node'))
    assert any(required in n.get('text','') and n.get('package') == 'dev.kunnect' for n in nodes), f'Wrong screen: {name}'
    (output / name).write_bytes(run('exec-out','screencap','-p'))
run('shell','cmd','uimode','night','no')
run('shell','cmd','statusbar','collapse')
run('shell','am','force-stop','dev.kunnect')
run('shell','am','start','-n','dev.kunnect/.MainActivity')
capture('android-home.png','Your devices.')
tap('Apps'); capture('android-apps.png','Less noise.')
tap('Settings'); capture('android-settings.png','Quietly')
tap('Connect'); tap('Connect your Mac'); capture('android-pairing.png','Meet your Mac.')
run('shell','input','keyevent','KEYCODE_BACK')
run('shell','cmd','uimode','night','yes')
capture('android-dark.png','Your devices.')
run('shell','cmd','uimode','night','no')
print('PASS: home, apps, settings, pairing, and dark mode screens rendered in emulator')
