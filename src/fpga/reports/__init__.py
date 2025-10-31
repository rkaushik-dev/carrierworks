"""What the build tools said about a design, in a form the bench can use.

The bench does not run synthesis and never will. It reads the reports the build
left behind, keeps the numbers that decide something, and throws the rest away.
Two numbers decide things: whether the design closed timing, and how much of
the device it used.
"""
