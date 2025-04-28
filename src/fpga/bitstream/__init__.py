"""Reading the files the build tools hand over.

A bitstream arrives as a .bit file with a small header on it, and inside that
header is the only trustworthy statement of which device the image is for. The
file name says whatever the person who copied it decided, the directory says
what the build script decided, and neither has ever stopped anyone loading a
Kintex image into an Artix.
"""
