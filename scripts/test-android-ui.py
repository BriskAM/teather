#!/usr/bin/env python3
"""Exercise visible touch coordinates on an emulator; never grants notification access."""
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ADB = str(Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk'))) / 'platform-tools/adb')

def adb(*args):
    return subprocess.run([ADB, '-e', *args], check=True, capture_output=True).stdout.decode()

def tree():
    for _ in range(8):
        adb('shell', 'rm', '-f', '/sdcard/teather-touch.xml')
        try:
            result = adb('shell', 'uiautomator', 'dump', '/sdcard/teather-touch.xml')
        except subprocess.CalledProcessError:
            time.sleep(.3)
            continue
        if 'dumped to' in result:
            return ET.fromstring(adb('shell', 'cat', '/sdcard/teather-touch.xml'))
        time.sleep(.3)
    raise AssertionError('No Android UI tree')

def bounds(node):
    return tuple(map(int, re.findall(r'\d+', node.get('bounds'))))

def find(label, root=None):
    if root is not None:
        return next(n for n in root.iter('node') if n.get('text') == label)
    for _ in range(3):
        match = next((n for n in tree().iter('node') if n.get('text') == label), None)
        if match is not None:
            return match
        time.sleep(.5)
    raise StopIteration(label)

def tap(node, side='center'):
    x1, y1, x2, y2 = bounds(node)
    x = x1 + 8 if side == 'left' else x2 - 8 if side == 'right' else (x1 + x2) // 2
    adb('shell', 'input', 'tap', str(x), str((y1 + y2) // 2))
    time.sleep(.5)

def button(label):
    root = tree()
    target = find(label, root)
    parents = {child: parent for parent in root.iter() for child in parent}
    while target.get('clickable') != 'true':
        target = parents[target]
    return target

def expect(fragment):
    assert any(fragment in n.get('text', '') for n in tree().iter('node')), fragment

def scroll_to(label):
    for _ in range(7):
        try:
            return button(label)
        except StopIteration:
            width, height = map(int, re.findall(r'\d+', adb('shell', 'wm', 'size'))[-2:])
            adb('shell', 'input', 'swipe', str(width // 2), str(height * 3 // 4), str(width // 2), str(height // 3), '350')
    raise AssertionError(f'Missing action: {label}')

def launch():
    adb('shell', 'am', 'force-stop', 'dev.kunnect')
    # Settings activities can outlive the app in its task. Start with a fresh task.
    adb('shell', 'am', 'start', '-W', '-f', '0x10008000', '-n', 'dev.kunnect/.MainActivity')
    find('Connect')

listener = 'dev.kunnect/.NotificationBridge'
listeners = adb('shell', 'settings', 'get', 'secure', 'enabled_notification_listeners')
was_allowed = 'dev.kunnect/' in listeners
font_scale = adb('shell', 'settings', 'get', 'system', 'font_scale').strip()
try:
    adb('shell', 'cmd', 'notification', 'disallow_listener', listener)
    for scale in os.environ.get('TEATHER_UI_FONT_SCALES', '1.0,1.3').split(','):
        adb('shell', 'settings', 'put', 'system', 'font_scale', scale)
        time.sleep(1)
        launch()
        print(f'Testing touch coordinates at font scale {scale}', flush=True)
        # Tap the actual label and icon area, rather than calling a semantic click.
        for tab, heading in [('Apps', 'Less noise.'), ('Files', 'Your files.'), ('Settings', 'Quietly'), ('Connect', 'Your devices.')]:
            tap(find(tab)); expect(heading)
        tap(button('Settings'), 'right'); expect('Quietly')
        title = 'Hide message content'
        row = button(title)
        initial = row.get('checked')
        assert row.get('checkable') == 'true'
        tap(find(title))
        assert button(title).get('checked') != initial, 'Tapping the label did not toggle'
        tap(button(title), 'right')
        assert button(title).get('checked') == initial, 'Switch edge did not restore state'
        # A card action must open setup at both visual edges.
        tap(scroll_to('Set up notification access'), 'left')
        expect('Connect your notifications')
        expect('If Android blocks access')
        tap(find('Later'))
        tap(scroll_to('Set up notification access'), 'right')
        expect('Connect your notifications')
        tap(scroll_to('Open App info'))
        expect('Teather')
        assert 'com.android.settings' in adb('shell', 'dumpsys', 'activity', 'activities')
        adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
        expect('Connect your notifications')
        tap(button('Notification settings'))
        expect('Teather notification sync')
        # This is the real Settings access screen; no permission is auto-granted.
        assert 'NotificationAccess' in adb('shell', 'dumpsys', 'activity', 'activities')
        print(f'PASS: visible navigation, label/switch/edge taps, permission help and system deep links (font {scale})', flush=True)
finally:
    if font_scale == 'null':
        adb('shell', 'settings', 'delete', 'system', 'font_scale')
    else:
        adb('shell', 'settings', 'put', 'system', 'font_scale', font_scale)
    if was_allowed:
        adb('shell', 'cmd', 'notification', 'allow_listener', listener)
    launch()
