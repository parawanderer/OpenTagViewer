#!/bin/sh

# https://stackoverflow.com/a/20703594

# mkdir OpenTagViewer.iconset
rm -rf OpenTagViewer.iconset/*

SOURCE_IMAGE=assets/opentagviewer_macos.png

sips -z 16 16     $SOURCE_IMAGE --out OpenTagViewer.iconset/icon_16x16.png
sips -z 32 32     $SOURCE_IMAGE --out OpenTagViewer.iconset/icon_16x16@2x.png
sips -z 32 32     $SOURCE_IMAGE --out OpenTagViewer.iconset/icon_32x32.png
sips -z 64 64     $SOURCE_IMAGE --out OpenTagViewer.iconset/icon_32x32@2x.png
sips -z 128 128   $SOURCE_IMAGE --out OpenTagViewer.iconset/icon_128x128.png
sips -z 256 256   $SOURCE_IMAGE --out OpenTagViewer.iconset/icon_128x128@2x.png
sips -z 256 256   $SOURCE_IMAGE --out OpenTagViewer.iconset/icon_256x256.png
sips -z 512 512   $SOURCE_IMAGE --out OpenTagViewer.iconset/icon_256x256@2x.png
sips -z 512 512   $SOURCE_IMAGE --out OpenTagViewer.iconset/icon_512x512.png
sips -z 1024 1024 $SOURCE_IMAGE --out OpenTagViewer.iconset/icon_512x512@2x.png

iconutil -c icns OpenTagViewer.iconset