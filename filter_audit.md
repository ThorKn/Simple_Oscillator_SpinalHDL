# **spinalSynth**: Filter Design Audit & Evaluation

This document presents a comprehensive evaluation of the State Variable Filter (SVF) specification and its integration into the **spinalSynth** system.

---

## 1. Pipeline Timing & Latency Alignment (Critical Finding)

During the audit of the top-level synthesizer wiring in [Synth.scala](file:///home/moss/Documents/hardware/AI_Audio_HW/spinalSynth/src/main/scala/synth/Synth.scala), a critical timing misalignment was identified and resolved:

### Previous Direct Cascaded Latency
- The audio path is: `Oscillator` $\rightarrow$ `envAttenuator` (1 cycle) $\rightarrow$ `masterAttenuator` (1 cycle) $\rightarrow$ `Decimator`.
- Due to the 2 registered attenuators, the decimator sampling tick was delayed by 2 cycles to align with the audio data.
- Introducing a filter with custom FSM arithmetic latency usually requires cascading more arbitrary delay lines in `Synth.scala`.

### Elegant Re-alignment via Flow Valids
- By design, the SVF calculation completes in 8 clock cycles, which fits comfortably within the 50-cycle window between `phaseTick` pulses.
- Instead of outputting the valid signal as soon as the FSM finishes, we hold the output sample and sync the outgoing `sampleOut.valid` to pulse exactly on the **next** `phaseTick` boundary.
- Because the outgoing `valid` is re-aligned to the raw 480 kHz `phaseTick` grid, the total path latency is standardized to exactly 1 sample period (50 system clock cycles), independent of the internal calculation latency or the preceding attenuator register delays.
- **Action Required**: The hardcoded decimation delay in `Synth.scala` is completely eliminated. The `decimator.io.sampleTick` can be connected directly to `timingGen.io.sampleTick` (`cycleCount = 0`).

---

## 2. Fixed-Point Arithmetic & Parameter Mapping Consistency

### 2.1 Coefficient Widths & Multiplication Safety
- **Cutoff (`cutoffCoeff` / 12-bit)**:
  - Downshifting by 12 means the coefficient $f$ is represented as $f / 4096$, covering a range of $[0, 1.0)$. Mathematically, the Chamberlin filter is stable for $f < 2.0$. A limit of $1.0$ guarantees absolute numerical stability for all configurations.
- **Resonance (`resonanceCoeff` / 8-bit)**:
  - Downshifting by 8 means the damping coefficient $d$ is represented as $d / 256$, covering a range of $[0, 1.0)$ (minimum $Q = 1.0$).
  - *Note*: If a highly damped response is ever required in the future (critical damping at $Q = 0.5$, which requires $d = 2.0$), the mapping or shift size would need to be modified. For our current specifications, the $[0, 1.0)$ damping range is consistent and stable.
- **Zero-Extension**:
  - The coefficients are unsigned, but the states are signed. We must zero-extend coefficients by 1 bit (`intoSInt`) before multiplying them with signed states to prevent SpinalHDL from synthesizing unsigned multipliers, which would corrupt the sign bit of the states.

### 2.2 Arithmetic Width Propagation
The step-by-step sizing mapped out in `filter_implementation.md` is fully consistent with `Filter_specs.md`:
1. `bp * resonance` is computed as `SInt(33 bits)` and immediately resized to `24 bits` after the shift (`>> 8`).
2. `hp` subtraction temporarily grows to `26 bits` before being resized to the target `24 bits`.
3. `bpNext` and `lpNext` additions grow to `25 bits` before being resized back to `24 bits`.

---

## 3. Register Map & Control Consistency

The proposed register layout fits perfectly within the unused space of `RegisterBank.scala`:
- `0x50` $\rightarrow$ `FILTER_ENABLE`
- `0x51` $\rightarrow$ `FILTER_MODE`
- `0x52` $\rightarrow$ `FILTER_CUTOFF`
- `0x53` $\rightarrow$ `FILTER_RESONANCE`

### Enable/Disable State Management
- **Disable State Reset**: When `enable` is deasserted, the filter FSM returns to `IDLE` and resets `lp` and `bp` to `0`. This is highly recommended to prevent stale resonant feedback from generating audible pops/clicks upon re-enabling the filter.
- **Synchronous Output Gating**: When `enable` is deasserted, `sampleOut.valid` continues to pulse synchronously with `phaseTick` but with the payload held at `0`. This keeps the downstream pipeline (the Decimator and I2S Transmitter) active and prevents timing gaps in the hardware output.

---

## 4. Summary of Alignment Actions
To successfully integrate the Filter Module into the `spinalSynth` project, the following file updates are planned:
1. **[Types.scala](file:///home/moss/Documents/hardware/AI_Audio_HW/spinalSynth/src/main/scala/synth/common/Types.scala)**: Define `FilterConfig` bundle.
2. **[RegisterBank.scala](file:///home/moss/Documents/hardware/AI_Audio_HW/spinalSynth/src/main/scala/synth/uart/RegisterBank.scala)**: Map control registers `0x50-0x53`.
3. **[Synth.scala](file:///home/moss/Documents/hardware/AI_Audio_HW/spinalSynth/src/main/scala/synth/Synth.scala)**: Wire the filter into the pipeline and remove the top-level decimator delay (setting it directly to `timingGen.io.sampleTick`).

