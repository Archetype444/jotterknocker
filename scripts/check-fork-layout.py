#!/usr/bin/env python3
"""Fail a sync before publishing if upstream removed the fork's layout contract."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'


def check(root=ROOT):
    java = root / 'app/src/main/java/app/olauncher'
    constants = (java / 'data/Constants.kt').read_text()
    for name, value in [('MAX_HOME_APPS', 15), ('HOME_APP_COLUMNS', 3), ('HOME_APP_ROWS', 5)]:
        assert re.search(rf'const val {name}\s*=\s*{value}\b', constants), name

    home = (java / 'ui/HomeFragment.kt').read_text()
    assert 'extraSlots = (9..15)' in home
    assert 'all.chunked(if (columns == 3) 5' in home
    assert 'HomeGridSizing.rowHeight' in home
    assert 'if (compact && !showingTip) 8 else prefs.homeBottomMargin' in home
    assert 'else 3)' in (java / 'data/Prefs.kt').read_text()
    assert 'EXPAND_STATUS_BAR' not in (root / 'app/src/main/AndroidManifest.xml').read_text()
    assert 'expandNotificationDrawer' not in home
    print('Fork contract verified: 15 slots, 3 columns, 5 rows, preserved area sizing.')


if __name__ == '__main__':
    check()
