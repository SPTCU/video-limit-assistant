# -*- coding: utf-8 -*-
"""把 classes.dex 追加进 APK（保留原有条目与对齐信息）"""
import os
import sys
import zipfile


def add_dex(apk_path, dex_path):
    tmp = apk_path + ".tmp"
    zin = zipfile.ZipFile(apk_path, "r")
    zout = zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED)
    for item in zin.infolist():
        zout.writestr(item, zin.read(item.filename))
    zout.write(dex_path, "classes.dex")
    zout.close()
    zin.close()
    os.replace(tmp, apk_path)


if __name__ == "__main__":
    add_dex(sys.argv[1], sys.argv[2])
    print("dex added into", sys.argv[1])
