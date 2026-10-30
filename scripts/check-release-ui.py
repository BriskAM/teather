#!/usr/bin/env python3
"""Run on the release APK installed in an emulator, after capture-android.py."""
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
adb = str(Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk'))) / 'platform-tools/adb')
def run(*args): return subprocess.run([adb,'-e',*args],capture_output=True,check=True).stdout
def tree():
    run('shell','uiautomator','dump','/sdcard/kunnect-ui.xml')
    return ET.fromstring(run('shell','cat','/sdcard/kunnect-ui.xml'))
def press(node):
    a,b,c,d = map(int,re.findall(r'\d+',node.get('bounds')))
    run('shell','input','tap',str((a+c)//2),str((b+d)//2))
def tap(text): press(next(n for n in tree().iter('node') if n.get('text') == text))
run('shell','am','force-stop','dev.kunnect')
run('shell','am','start','-n','dev.kunnect/.MainActivity')
tap('Connect your Mac')
fields = [n for n in tree().iter('node') if n.get('class') == 'android.widget.EditText']
press(fields[0]); run('shell','input','text','127.0.0.1')
# Host validation runs first; leaving the key empty avoids handling real credentials.
# Scroll the form if the software keyboard reduces the available space.
if not any(n.get('text') == 'Pair & start syncing' for n in tree().iter('node')):
    run('shell','input','swipe','540','1450','540','600','400')
tap('Pair & start syncing')
assert any('Use the 100.64' in n.get('text','') for n in tree().iter('node')), 'Release must reject loopback'
run('shell','input','keyevent','KEYCODE_BACK')
print('PASS: signed release pairing rejects loopback; emulator-only transport bypass is absent')
