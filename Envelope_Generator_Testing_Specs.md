# spinalSynth: Envelope Generator Testing Specification

This document provides the formal specifications for the simulation test suite of the Envelope Generator submodule in `spinalSynth`. It defines the verification logic, simulated environments, stimulus parameters, and assertion constraints for all unit and integration testbenches.

---

## 1. Unit Tests

This chapter contains the specifications for testing individual, isolated hardware modules of the Envelope Generator.

### 1.1 Control Logic Unit Test (`EnvelopeCtrlSim`)

#### Purpose
Verifies the ADSR state machine FSM built using SpinalHDL's `StateMachine` library, sync trigger propagation, playback direction logic, and compile-time logarithmic parameter-to-increment lookup ROM.

#### Simulated Environment
* **Component Under Test**: `EnvelopeCtrl`
* **Clock Domain**: 24 MHz master clock (simulation period = 10 units).
* **Reset**: Asynchronous, Active-High.

#### Input Stimulus & Signals
* `io.syncIn`: `Bool` (Hard/Soft sync line)
* `io.midiClock`: `Bool` (MIDI clock division reference)
* `io.config`: `EnvelopeConfig` (Input registers)
* `io.segmentDone`: `Bool` (Accumulator target completion)
* `io.resetAccum`: `Bool` (Output to reset accumulator register)
* `io.runAccum`: `Bool` (Output to run accumulator register)
* `io.accumDir`: `Bool` (Output direction)
* `io.phaseInc`: `UInt` (22 bits, phase step value)
* `io.curveSelect`: `UInt` (2 bits, curve selector)
* `io.activeStage`: `UInt` (3 bits, state stage output)

#### Test Cases

##### 1.1.1 Reset Stability
* **Action**: Assert active-high `reset` for 5 clock cycles while driving random stimulus values on `io.syncIn`, `io.midiClock`, `io.config`, and `io.segmentDone`.
* **Assertion**: Verify that all control outputs are strictly held at their idle states: `io.resetAccum = False`, `io.runAccum = False`, `io.accumDir = False`, `io.phaseInc = 0`, `io.curveSelect = 0`, and `io.activeStage = 0`.

##### 1.1.2 Normal ADSR State Transitions
* **Action**: Configure standard parameters and toggle the Gate register bit `config.ctrl[1]`:
  1. Toggle Gate `Low -> High` while FSM is in `IDLE`.
     * *Assertion*: FSM transitions to `ATTACK` (activeStage = 1), asserts `resetAccum = True` and `runAccum = True` for 1 cycle.
  2. Pulse `segmentDone` High for 1 cycle.
     * *Assertion*: FSM transitions to `DECAY` (activeStage = 2), asserts `resetAccum = True` for 1 cycle.
  3. Pulse `segmentDone` High for 1 cycle.
     * *Assertion*: FSM transitions to `SUSTAIN` (activeStage = 3), disables phase tracking (`runAccum = False`).
  4. Toggle Gate `High -> Low`.
     * *Assertion*: FSM transitions to `RELEASE` (activeStage = 4), asserts `resetAccum = True` and `runAccum = True` for 1 cycle.
  5. Pulse `segmentDone` High for 1 cycle.
     * *Assertion*: FSM transitions back to `IDLE` (activeStage = 0), clears `runAccum = False`.

##### 1.1.3 Gate Interruption & Re-triggering
* **Action**: Interrupt active phases with unexpected Gate toggles:
  - Trigger `Gate ON`, transition to `ATTACK`, then toggle `Gate OFF` halfway through.
    * *Assertion*: FSM transitions instantly to `RELEASE` (activeStage = 4).
  - Trigger `Gate OFF`, transition to `RELEASE`, then toggle `Gate ON` halfway through.
    * *Assertion*: FSM transitions instantly to `ATTACK` (activeStage = 1) and resets accumulator (`resetAccum = True`).

##### 1.1.4 Looping (LFO) Mode
* **Action**: Set Loop Enable register `config.ctrl[2] = True`. Trigger `Gate ON`. Allow FSM to transition through `ATTACK` to `DECAY`. Pulse `segmentDone` High.
* **Assertion**: Verify that FSM transitions instantly from `DECAY` back to `ATTACK` (activeStage = 1) instead of going to `SUSTAIN`, resetting the accumulator cleanly.

##### 1.1.5 Logarithmic Increment ROM Mapping
* **Action**: Write specific values to the `attack` register and read `phaseInc` outputs:
  - Write `ENV_ATTACK = 0` (minimum rate parameter).
    * *Assertion*: Verify `phaseInc` is exactly `357914` (0x05761A), yielding a 0.5 ms transient.
  - Write `ENV_ATTACK = 255` (maximum rate parameter).
    * *Assertion*: Verify `phaseInc` is exactly `6` (0x000006), yielding a 30.0 s transient.

---

### 1.2 Phase Accumulator Unit Test (`EnvelopeAccumulatorSim`)

#### Purpose
Verifies the 32-bit register accumulator, phase counting direction, split output mapping, and segment overflow boundaries.

#### Simulated Environment
* **Component Under Test**: `EnvelopeAccumulator`
* **Clock Domain**: 24 MHz master clock (simulation period = 10 units).
* **Reset**: Asynchronous, Active-High.

#### Input Stimulus & Signals
* `io.resetAccum`: `Bool`
* `io.runAccum`: `Bool`
* `io.accumDir`: `Bool` (0 = Forward, 1 = Reverse)
* `io.phaseInc`: `UInt` (22 bits)
* `io.segmentDone`: `Bool` (Output complete)
* `io.baseIndex`: `UInt` (8 bits, output address)
* `io.fraction`: `UInt` (2 bits, output fraction)

#### Test Cases

##### 1.2.1 Reset Defaults
* **Action**: Assert active-high `reset` for 5 clock cycles while driving arbitrary inputs on `runAccum = True`, `accumDir = True`, and `phaseInc = 0x200000` (valid 22-bit value).
* **Assertion**: Verify that the internal phase register remains strictly at `0`, outputting `baseIndex = 0`, `fraction = 0`, and `segmentDone = False`.

##### 1.2.2 Phase Accumulation & Splitting Precision
* **Action**: Drive `phaseInc = 0x200000` ($2^{21}$). Enable the accumulator.
* **Assertion**: Verify the splitting output precision:
  - Cycle 1: `baseIndex = 0`, `fraction = 0`.
  - Cycle 2: `baseIndex = 0`, `fraction = 1`.
  - Cycle 3: `baseIndex = 0`, `fraction = 1`.
  - Cycle 4: `baseIndex = 0`, `fraction = 2`.
  - Also verify fraction-only tracking with `phaseInc = 0x200000` using a 2-cycle wait over 3 steps (each step increments `fraction` by exactly `1`).

##### 1.2.3 Forward Wrap Done
* **Action**: Set `accumDir = False` (Forward), load `phaseInc = 0x200000`. Run for 2048 cycles to wrap the 32-bit accumulator.
* **Assertion**: Verify that on the 2048th cycle, the register overflows, asserting `segmentDone = True` for exactly 1 cycle.

##### 1.2.4 Reverse Underflow Done
* **Action**: Assert `resetAccum = True` to clear the counter to `0`. Set `accumDir = True` (Reverse), load `phaseInc = 0x200000`. Enable accumulator.
* **Assertion**: Verify that on the very first cycle, the counter underflows past zero, asserting `segmentDone = True` instantly for 1 cycle.

---

### 1.3 Wave Shaper Unit Test (`EnvelopeShaperSim`)

#### Purpose
Verifies the 257-entry LUT curves, shift-add combinational linear interpolation, unipolar-to-bipolar digital scaling, and audio heartbeat gating.

#### Simulated Environment
* **Component Under Test**: `EnvelopeShaper`
* **Clock Domain**: 24 MHz master clock (simulation period = 10 units).
* **Reset**: Asynchronous, Active-High.

#### Input Stimulus & Signals
* `io.phaseTick`: `Bool` (Audio sample gating tick)
* `io.baseIndex`: `UInt` (8 bits)
* `io.fraction`: `UInt` (2 bits)
* `io.curveSelect`: `UInt` (2 bits)
* `io.sustainLevel`: `UInt` (8 bits)
* `io.activeStage`: `UInt` (3 bits)
* `io.envelopeOut`: `Flow[UInt]` (10 bits)
* `io.envelopeOutSigned`: `Flow[SInt]` (10 bits)

#### Test Cases

##### 1.3.1 Reset Stability
* **Action**: Assert active-high `reset` for 5 clock cycles while driving active `phaseTick = True` and non-zero inputs.
* **Assertion**: Verify that output flow streams are strictly quiet and zeroed: `envelopeOut.valid = False`, `envelopeOut.payload = 0`, `envelopeOutSigned.valid = False`, and `envelopeOutSigned.payload = 0`.

##### 1.3.2 Multiplierless Shift-Add Accuracy
* **Action**: Set `curveSelect = 00` (Linear). Set `baseIndex = 10` (so Y0 = 10, Y1 = 11). Drive different values on `fraction`:
  - `fraction = 00`: Assert `envelopeOut.payload = 40` (Y0 interpolated scale).
  - `fraction = 01`: Assert `envelopeOut.payload = 41` (interpolated Y0 + 1/4).
  - `fraction = 10`: Assert `envelopeOut.payload = 42` (interpolated Y0 + 2/4).
  - `fraction = 11`: Assert `envelopeOut.payload = 43` (interpolated Y0 + 3/4).

##### 1.3.3 Parallel Bipolar Bitwise Scaling
* **Action**: Monitor the relationship between unipolar and bipolar outputs across different phase positions.
* **Assertion**: Verify that `io.envelopeOutSigned.payload` is exactly equal to `(io.envelopeOut.payload ^ 0x200).asSInt`, confirming that the unipolar range (0 to 1023) maps perfectly to the center-zero bipolar range (-512 to +511) without logic delays or arithmetic units.

##### 1.3.4 Sample Rate Flow Validation
* **Action**: Toggle `phaseTick` between `True` and `False`.
* **Assertion**: Verify that both `envelopeOut.valid` and `envelopeOutSigned.valid` follow the state of `phaseTick` synchronously with zero clock cycle delay.

---

## 2. Integration Tests

This chapter contains the specifications for verifying full-wrapper envelope simulation.

### 2.1 Complete Envelope Generator Integration Test (`EnvelopeGeneratorSim`)

#### Purpose
Verifies the integration of the three submodules, validating the complete 3-cycle pipeline latency, and the delay-matched FSM transition timing.

#### Simulated Environment
* **Component Under Test**: `EnvelopeGenerator`
* **Clock Domain**: 24 MHz master clock (simulation period = 10 units).
* **Reset**: Asynchronous, Active-High.

#### Input Stimulus & Signals
* `io.phaseTick`: `Bool`
* `io.syncIn`: `Bool`
* `io.midiClock`: `Bool`
* `io.config`: `EnvelopeConfig`
* `io.envelopeOut`: `Flow[UInt]`
* `io.envelopeOutSigned`: `Flow[SInt]`

#### Test Cases

##### 2.1.1 Power-On Reset & Boot Stability (Reset Test)
* **Action**: Assert active-high `reset` for 10 clock cycles while driving `phaseTick = True`, pulsing `syncIn = True`, and setting random non-zero values on the `config` envelope control parameters.
* **Assertion**: Verify that throughout the reset duration and for the 3-cycle pipeline latency window following reset deassertion:
  - Both `envelopeOut.valid` and `envelopeOutSigned.valid` remain strictly `False`.
  - Both unipolar and bipolar payloads are strictly held at `0`.

##### 2.1.2 Standard ADSR Envelope Playback (Standard Test)
* **Action**: Configure standard linear ADSR parameters (e.g. `attack = 100`, `decay = 100`, `sustain = 128`, `release = 100`) and drive the playback cycle:
  1. Pulse Gate register `Low -> High` (ON). Run simulation until FSM hits `SUSTAIN`.
  2. Pulse Gate register `High -> Low` (OFF). Run simulation until FSM completes the envelope.
* **Assertion**: Verify the complete envelope profile:
  - **Attack Stage:** The unipolar output climbs monotonically to peak amplitude `1023`.
  - **Decay Stage:** The unipolar output decays smoothly to the sustain level of `512` (scaled `0x80`).
  - **Sustain Stage:** The output remains locked strictly at `512` with zero cycle-to-cycle amplitude drift.
  - **Release Stage:** The output fades monotonically back to `0`.

##### 2.1.3 Pipeline Latency & Sustain Clamping Sync (Standard Test)
* **Action**: Trigger `Gate ON` at Cycle 0. Drive `phaseTick = True` continuously. Step cycle-by-cycle as FSM transitions from `DECAY` to `SUSTAIN` with sustain configured to `0x80` (`512`).
* **Assertion**: Verify that:
  - The first non-zero output value appears exactly at **Cycle 3** (verifying the 3-cycle data pipeline).
  - The output transition from `DECAY` to `SUSTAIN` occurs exactly 3 cycles after the FSM state changes, proving that `Stage_Delay` matches the pipeline delay perfectly and eliminates any premature clamping spikes or drops.

##### 2.1.4 Simultaneous Gate and Sync Conflict (Edge Case Test)
* **Action**: Pulse both `Gate ON` and `syncIn` High in the exact same master clock cycle.
* **Assertion**: Hard sync must take absolute priority. The internal accumulator must instantly reset to `0` and FSM must immediately restart in the `ATTACK` stage (activeStage = 1).

##### 2.1.5 Ultra-High Frequency Gate Chattering (Irrational Behavior Test)
* **Action**: Drive the Gate bit rapidly `ON -> OFF -> ON -> OFF` every 2 clock cycles, simulating extreme physical keyboard bouncing or high-frequency automated trigger chatter.
* **Assertion**: Verify that the envelope generator maintains absolute stability:
  - The state machine transitions safely without entering illegal states.
  - No counter wrapping, deadlock, or phase register leakage occurs.
  - Output values remain strictly bounded within the safe unipolar/bipolar envelopes (0 to 1023) without arithmetic overflow glitches.

##### 2.1.6 Dynamic Mid-Flight Curve Switching (Irrational Behavior Test)
* **Action**: Trigger a standard linear ADSR envelope. Halfway through the `ATTACK` phase, dynamically change the curve selection register `config.ctrl[6:5]` from Linear (`00`) to Exponential (`01`) mid-transition.
* **Assertion**: Verify that the wave shaper accepts the new parameter instantly on the next clock cycle and interpolates smoothly from the current amplitude value using the new exponential ROM table, without causing output signal discontinuities, pops, or wrapping glitches.
