"""Die temperature: reading it, deciding about it, and acting on it.

The bench polls the system monitor on every configured device it holds a lease
on. What it does with those numbers is the interesting part, and it is
deliberately split three ways: the samples are just data, the alarm state is a
function of the samples and the limits, and the supervisor is the only thing
that acts.
"""
