# spinalSynth: Architectural Review & Audit Report

This document compiles the architectural review, functional audit, and documentation verification results conducted for the `spinalSynth` synthesizer project. It highlights functional gaps, naming and case conventions, bit-width arithmetic alignment, documentation readability, and a recommended action plan for the repository clean-up.

---

## Table of Contents

1. Major Functional Gaps (RTL vs. Specs)
2. Naming & Case Style Consistency
3. Bit-Widths & Arithmetic Scaling Alignment
4. Documentation Quality & Readability
5. Clean-Up Party Recommendations

---

## 1. Major Functional Gaps (RTL vs. Specs)

We identified two notable gaps where features are fully defined in the hardware configuration types, implemented and tested inside the register bank, and described in the specification documents, but are completely unused or unimplemented inside the core envelope generator package:

### Gap A: Unimplemented Envelope Phase Offset (`ENV_PHASE_OFFSET`, Address `0x46`)
* **Specification Definition**: 
  * `Types.scala` registers `val phaseOffset = UInt(8 bits)` inside the `EnvelopeConfig` bundle.
  * `RegisterBank.scala` stages and outputs `phaseOffset` at address `0x46` (which is fully covered by tests in `RegisterBankSim`).
  * `Testing_specs.md` specifies assertions to verify the proper write and update behavior of this register.
* **Actual RTL Behavior**: In `EnvelopeGenerator.scala`, the port `io.config.phaseOffset` is completely ignored and is **never wired to any submodule** (neither `EnvelopeCtrl`, `EnvelopeAccumulator`, nor `EnvelopeShaper`). It has no functional impact on the generated envelope output.

### Gap B: Unimplemented MIDI Clock Synchronization (`midiClock`)
* **Specification Definition**: 
  * `Implementation_specs.md` states: *"MIDI Sync: When enabled, the phase increment phaseInc is scaled according to incoming midiClock ticks rather than the default time register mapping."*
  * `Synth.scala` wires `envGen.io.midiClock := False` as a system-level placeholder.
  * `Testing_specs.md` defines it as a simulated input.
* **Actual RTL Behavior**: In `EnvelopeCtrl.scala`, `io.midiClock` is defined as an input port, but it is **never read or integrated** into the lookup logic or the ADSR FSM state transitions. MIDI synchronization is functionally absent from the actual synthesis engine.

---

## 2. Naming & Case Style Consistency

Overall, the naming conventions in the RTL and tests are exceptionally clean, professional, and well-structured, but we noted some minor inconsistencies between the Scala variables and the Markdown specification terms:

### Case Mismatches in Registers
* **RTL Naming**: In `RegisterBank.scala`, the internal registers use standard camelCase: `waveformReg`, `pulseWidthReg`, `volumeReg`, `envCtrlReg`, `envAttackReg`, `envDecayReg`, `envSustainReg`, `envReleaseReg`, `envSyncCtrlReg`, and `envPhaseOffsetReg`.
* **Specification Naming**: `Implementation_specs.md` uses capital snake_case shorthand or spaces, such as `FREQ_LOW`, `FREQ_MID`, `FREQ_HIGH`, `WAVE_SEL`, `PWM_WIDTH`, `VOLUME`, `ENV_CTRL`, `ENV_ATTACK`, etc.
* **Assessment**: This is standard and highly readable. The spec represents the conceptual register map macros, while the RTL represents specific hardware registers.

---

## 3. Bit-Widths & Arithmetic Scaling Alignment

The math across the blocks matches beautifully. We checked the arithmetic scaling chain:
1. **Unipolar Output**: `EnvelopeShaper.scala` scales the unipolar output cleanly to a **10-bit range (0 to 1023)**.
2. **Bipolar Output**: It correctly shifts to a **10-bit signed range (-512 to +511)** via direct bitwise inversion of the MSB (`finalValUnipolarClamped ^ 0x200`), avoiding an adder block. This matches both `Testing_specs.md` and `Implementation_specs.md` perfectly.
3. **Cascaded Volume Scaling**:
   * `envAttenuator` is instantiated as `Attenuator(volumeWidth = 10)` in `Synth.scala`.
   * `attenuator` is instantiated with the default `volumeWidth = 8` (master volume).
   * Positive and negative peak assertions (`32607` and `-32609`) in `SynthSim.scala` align exactly with the cascaded math of both attenuators:
     $$\text{Pos Peak} = \left\lfloor 32767 \times \frac{1023}{1024} \times \frac{255}{256} \right\rfloor = 32607$$
     $$\text{Neg Peak} = \left\lfloor -32768 \times \frac{1023}{1024} \times \frac{255}{256} \right\rfloor = -32609 \quad (\text{shift-right rounding towards } -\infty)$$

This is a phenomenal mathematical alignment across docs, RTL, and simulation testbenches.

---

## 4. Documentation Quality & Readability

The documentation is exceptionally comprehensive, featuring Mermaid diagram flowcharts, step-by-step test descriptions, Verilator loop bypass best practices, and clean ASCII timing chains.

### Small Discrepancies:
1. **Bypass Bit Mapping Location**: In `Implementation_specs.md`, section 2 mentions: *"if envelope Enable (ENV_CTRL bit 0) is 0..."*. However, in `EnvelopeCtrl.scala`, gate input is mapped to bit 1: `val gateOn = io.config.ctrl(1)`. Bit 0 is strictly used as the master global bypass in `Synth.scala` (`val envBypassed = !uart.io.envConfig.ctrl(0)`). This is a very subtle difference: bit 0 bypasses the attenuator scaling at the top-level, and bit 1 controls the FSM keyboard gate triggers. The specs should clarify this distinction to avoid confusion.
2. **Table of Contents Consistency**: In `Implementation_specs.md`, section 4 is titled `## 4. Uart & Registers`, but in the Table of Contents, it was written as `4. Uart Subsystem`. This mismatch has been corrected in the latest document updates.

---

## 5. Clean-Up Party Recommendations

Here is the proposed action plan for our clean-up party:

### Phase 1: Harmonize the Specs (Document Changes)
To maintain the "compact and deterministic" philosophy of the project, if we do not need `phaseOffset` and `midiClock` for the next hardware iteration, we should **remove them** from the configurations and clean up the unused code. If we do want to keep them for future expansion, we should explicitly document them as *"staged placeholders for future DSP expansion, currently bypassed in the core envelope logic"*.

### Phase 2: Refine Block Comments
* Add explicit inline comments in `EnvelopeGenerator.scala` documenting that `io.config.phaseOffset` and `io.midiClock` are currently unused placeholders to avoid confusion for future developers.
* Document the functional difference between `ENV_CTRL` bit 0 (Top-Level Attenuation Bypass) and bit 1 (FSM Keyboard Gate trigger) in the main register table.
