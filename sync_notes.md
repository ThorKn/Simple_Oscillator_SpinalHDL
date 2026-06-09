# Between Note ON/OFF and sync\clock signal:

## Option A: Free running, No sync, only Note ON/OFF

The sync signal is ignored for note timing.

## Option B: The "Gated Rhythmic Stutter" (Both interact)

This is the most common setup for synced envelopes. The note Gate ON acts as an enable switch:

Idle: While no key is pressed, the envelope rests at 0.
Key Pressed (Gate ON): The envelope triggers instantly (starts the Attack phase) so that the sound begins without latency.
While Held: As long as you hold the key, the envelope automatically re-triggers on every beat division (e.g., every 16th note) aligned with the MIDI clock. This creates a pulsing, rhythmic effect (often called a "gator" effect).
Key Released (Gate OFF): The rhythmic triggering stops, and the envelope transitions to the Release phase to fade out.

## Option C: The "LFO Mode" (Gate ON is ignored)

In this mode, the envelope acts strictly as a Beat-Synced Low Frequency Oscillator (LFO):

The envelope loops continuously in the background, locked to the DAW tempo.
The physical note Gate ON does not trigger the envelope. Instead, the note press simply opens the audio path (VCA) so you can hear the continuous modulation wave that is already running.


