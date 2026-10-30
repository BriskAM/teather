#!/usr/bin/env python3
"""Run on the release APK installed in an emulator, after capture-android.py."""
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
adb = str(Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk'))) / 'platform-tools/adb')
def run(*args): return subprocess.run([adb,*args],capture_output=True,check=True).stdout
def tree():
    run('shell','uiautomator','dump','/sdcard/kunnect-ui.xml')
    return ET.fromstring(run('shell','cat','/sdcard/kunnect-ui.xml'))
def press(node):
    a,b,c,d = map(int,re.findall(r'\d+',node.get('bounds')))
    run('shell','input','tap',str((a+c)//2),str((b+d)//2))
def tap(text): press(next(n for n in tree().iter('node') if n.get('text') == text))
tap('Connect your Mac')
fields = [n for n in tree().iter('node') if n.get('class') == 'android.widget.EditText']
press(fields[0]); run('shell','input','text','127.0.0.1')
# Synthetic test key; it never leaves the pairing form because host validation fails.
press(fields[1]); run('shell','input','text','a'*64)
run('shell','input','keyevent','KEYCODE_BACK')
tap('Pair & start syncing')
assert any('Use the 100.64' in n.get('text','') for n in tree().iter('node')), 'Release must reject loopback'
run('shell','input','keyevent','KEYCODE_BACK')
print('PASS: signed release pairing rejects loopback; emulator-only transport bypass is absent')
