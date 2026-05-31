# spinalSynth: Envelope Generator Implementation Specification

## Table of Contents

1. RTL Hierarchy Updates
2. EnvelopeGenerator (Top-Level Wrapper)
   - IO Bundle
   - Internal Architecture & Submodules
3. EnvelopeCtrl
   - IO Bundle
   - State Machine Design
   - Playback & Sync Controller
   - Logarithmic Time-to-Increment Look-Up Table (ROM)
4. EnvelopeAccumulator
   - IO Bundle
   - Counter & Overflow Behavior
5. EnvelopeShaper
   - IO Bundle
   - ROM Curves (Lin, Exp, Log, S-Curve)
   - Multiplierless Hybrid 8+2 Linear Interpolation
6. Signal Flow & Pipeline Timing

---

## 1. RTL Hierarchy Updates

To integrate the Envelope Generator submodule cleanly into the existing project, the directory hierarchy is extended as follows:

```text
spinalSynth/
├── src/
│   ├── main/
│   │   └── scala/
│   │       └── synth/
│   │           ├── common/
│   │           │   └── Types.scala         # Add EnvelopeConfig bundle and Register addresses
│   │           ├── envelope/               # [NEW] Envelope generator package
│   │           │   ├── EnvelopeGenerator.scala   # Top-level integration & ports
│   │           │   ├── EnvelopeCtrl.scala        # State machine & LUT ROM
│   │           │   ├── EnvelopeAccumulator.scala # 32-bit phase accumulator
│   │           │   └── EnvelopeShaper.scala      # 257-entry ROM curves & interpolator
│   └── test/
│       └── scala/
│           └── synth/
│               └── envelope/               # [NEW] Envelope simulation tests
│                   └── EnvelopeSim.scala   # SpinalSim testbench for ADSR, modes & curves
```

---

## 2. EnvelopeGenerator (Top-Level Wrapper)

### Purpose
The `EnvelopeGenerator` top-level wrapper acts as the coordinator. It encapsulates the three core submodules (`EnvelopeCtrl`, `EnvelopeAccumulator`, and `EnvelopeShaper`), binds them together, registers parameters to the global register map, and packages the outputs into a synced flow rate.

### IO Bundle
```scala
class EnvelopeGenerator extends Component {
  val io = new Bundle {
    // Clock Heartbeat & Sync inputs
    val phaseTick = in Bool()                 // Heartbeat tick synced with 480 kHz audio rate
    val syncIn    = in Bool()                 // External trigger for Hard or Soft Sync
    val midiClock = in Bool()                 // External MIDI clock tick (24 PPQN pulse)
    val config    = in(EnvelopeConfig())      // Packaged register configurations

    // System Outputs
    val envelopeOut       = master(Flow(UInt(10 bits))) // Unipolar output (0 to 1023)
    val envelopeOutSigned = master(Flow(SInt(10 bits))) // Bipolar output (-512 to +511)
  }
}
```

### Internal Architecture
The wrapper instantiates the submodules and wires their control signals. The outputs from the `EnvelopeShaper` are driven directly to the system audio/control busses.

```scala
val ctrl        = new EnvelopeCtrl()
val accumulator = new EnvelopeAccumulator()
val shaper      = new EnvelopeShaper()

// Connecting Ctrl to Accumulator
accumulator.io.resetAccum   := ctrl.io.resetAccum
accumulator.io.runAccum     := ctrl.io.runAccum
accumulator.io.accumDir     := ctrl.io.accumDir
accumulator.io.phaseInc     := ctrl.io.phaseInc
ctrl.io.segmentDone         := accumulator.io.segmentDone

// Connecting Accumulator and Ctrl to Shaper
shaper.io.phaseTick    := io.phaseTick
shaper.io.baseIndex    := accumulator.io.baseIndex
shaper.io.fraction     := accumulator.io.fraction
shaper.io.curveSelect  := ctrl.io.curveSelect
shaper.io.sustainLevel := io.config.sustain
shaper.io.activeStage  := ctrl.io.activeStage

// Top-Level Outputs
io.envelopeOut       <> shaper.io.envelopeOut
io.envelopeOutSigned <> shaper.io.envelopeOutSigned
```

---

## 3. EnvelopeCtrl

### Purpose
The `EnvelopeCtrl` submodule serves as the "brain". It is responsible for parsing control registers, driving the ADSR playback state machine, computing direction and synchronization triggers, and fetching stage-appropriate increment values from a static lookup table.

### IO Bundle
```scala
class EnvelopeCtrl extends Component {
  val io = new Bundle {
    // Inputs from Top Wrapper
    val syncIn      = in Bool()
    val midiClock   = in Bool()
    val config      = in(EnvelopeConfig())
    val segmentDone = in Bool()               // High when accumulator sweeps to target limit

    // Outputs to Accumulator
    val resetAccum  = out Bool()
    val runAccum    = out Bool()
    val accumDir    = out Bool()              // 0 = Forward, 1 = Reverse
    val phaseInc    = out UInt(22 bits)

    // Outputs to Shaper
    val curveSelect  = out UInt(2 bits)
    val activeStage  = out UInt(3 bits)       // IDLE=0, ATTACK=1, DECAY=2, SUSTAIN=3, RELEASE=4
  }
}
```

### State Machine Design
The ADSR engine is implemented as a built-in SpinalHDL `StateMachine` (`spinal.lib.fsm`). 

```text
                  Gate ON (Reset Phase)
               ┌────────────────────────┐
               │                        v
  +─────────+  │  +──────────+  Done  +─────────+
  |  IDLE   |──┼─>|  ATTACK  |───────>|  DECAY  |
  +─────────+  │  +──────────+        +─────────+
       ^       │       │                   │
       │       │       │ Gate OFF          │ Done & Loop
  Done │       │       v (Reset)           │ (Reset)
       │       │  +──────────+             │
       │       └──| RELEASE  |<────────────┘
       │          +──────────+  Gate OFF / Done (No Loop)
       │               ^        (Reset)    │
       │               │                   v
       └───────────────┴──────────────+─────────+
                                      | SUSTAIN |
                                      +─────────+
```

It controls the envelope stages using five main states:

* **IDLE (Stage 0):** The default idle state. A `Gate ON` trigger resets the accumulator and transitions the FSM to `ATTACK`.
* **ATTACK (Stage 1):** The phase accumulator counts forward. If `Gate OFF` is detected, it transitions to `RELEASE`. When the segment completes (accumulator hits 1023), it resets the accumulator and transitions to `DECAY`.
* **DECAY (Stage 2):** The phase accumulator counts forward. If `Gate OFF` is detected, it transitions to `RELEASE`. When the segment completes, it transitions to `SUSTAIN` (or loops back to `ATTACK` if Looping/LFO mode is active).
* **SUSTAIN (Stage 3):** The accumulator is paused, holding the output at the configured sustain level. A `Gate OFF` trigger resets the accumulator and transitions the FSM to `RELEASE`.
* **RELEASE (Stage 4):** The phase accumulator counts forward. If `Gate ON` is triggered, it resets the accumulator and transitions to `ATTACK`. When the segment completes, it transitions back to `IDLE`.

### Playback & Sync Controller
* **Looping (LFO Mode):** When `config.ctrl[2]` (Loop Enable) is active, transitioning out of `DECAY` loops instantly back to `ATTACK` instead of going to `SUSTAIN`.
* **Reverse Mode:** When `config.ctrl[4]` is active, the accumulator direction is inverted (`accumDir := True`), altering the counting sequence.
* **Ping-Pong Mode:** When `config.ctrl[3]` is active, a forward segment completion triggers a reverse segment immediately on the same stage before transitioning states.
* **Sync Logic:**
  * **Hard Sync:** A rising edge on `syncIn` forces the FSM back to `ATTACK` and sets `resetAccum := True`.
  * **MIDI Sync:** When enabled, the phase increment `phaseInc` is scaled according to incoming `midiClock` ticks rather than the default time register mapping.

### Logarithmic Time-to-Increment Lookup Table (ROM)
Calculating the logarithmic time-duration mapping at runtime requires expensive divisor blocks. To ensure ASIC portability, the 256 increment coefficients are computed in Scala at compile-time and instantiated as a static hardware ROM (`Mem` in SpinalHDL).

#### Scala ROM Calculator Formula:
```scala
val clockFreq = 24000000.0   // 24 MHz
val tMin      = 0.0005       // 0.5 ms
val tMax      = 30.0         // 30.0 s

val lutContent = for (p <- 0 until 256) yield {
  val t = tMin * math.pow(tMax / tMin, p / 255.0)
  val inc = math.round((math.pow(2, 32) / (t * clockFreq)))
  U(inc, 22 bits)
}

val rom = Mem(UInt(22 bits), 256) init(lutContent)
```

---

## 4. EnvelopeAccumulator

### Purpose
The `EnvelopeAccumulator` is a high-speed 32-bit digital register that acts as the phase counter. It increments on every 24 MHz system clock cycle when enabled, driving the envelope's progress through time.

### IO Bundle
```scala
class EnvelopeAccumulator extends Component {
  val io = new Bundle {
    // Inputs from Control Unit
    val resetAccum  = in Bool()
    val runAccum    = in Bool()
    val accumDir    = in Bool()               // 0 = Forward (Up), 1 = Reverse (Down)
    val phaseInc    = in UInt(22 bits)

    // Outputs
    val segmentDone = out Bool()              // Boundary completion pulse
    val baseIndex   = out UInt(8 bits)        // LUT address (Upper 8 integer bits)
    val fraction    = out UInt(2 bits)        // Interpolation fraction (Lower 2 integer bits)
  }
}
```

### Counter & Overflow Behavior
* **Accumulator register:** `val phase = Reg(UInt(32 bits)) init(0)`
* **Accumulation Logic:**
  * If `io.resetAccum` is asserted, reset `phase := 0`.
  * If `io.runAccum` is High:
    * If `io.accumDir` is Forward (`False`), `phase := phase + io.phaseInc`.
    * If `io.accumDir` is Reverse (`True`), `phase := phase - io.phaseInc`.
* **Output Splitting:**
  * `io.baseIndex := phase(31 downto 24)`
  * `io.fraction  := phase(23 downto 22)`
* **Segment Boundaries & Done Detection:**
  * In **Forward Mode**, completion is hit when the phase register overflows (wraps past 32-bit maximum).
  * In **Reverse Mode**, completion is hit when the phase register underflows (wraps past 0).
  * `io.segmentDone := (Forward && overflow) || (Reverse && underflow)`

---

## 5. EnvelopeShaper

### Purpose
The `EnvelopeShaper` transforms the raw, linear accumulator phase into customized, musically natural curves. It reads two consecutive points from a 257-entry curve ROM (Lin, Exp, Log, S-Curve) based on the 8-bit Base Index, performs linear interpolation in pure multiplierless combinational logic using the 2-bit fraction, and outputs unipolar/bipolar audio-rate flows.

### IO Bundle
```scala
class EnvelopeShaper extends Component {
  val io = new Bundle {
    val phaseTick    = in Bool()              // Gating tick
    val baseIndex    = in UInt(8 bits)
    val fraction     = in UInt(2 bits)
    val curveSelect  = in UInt(2 bits)        // 00=Lin, 01=Exp, 10=Log, 11=S-Curve
    val sustainLevel = in UInt(8 bits)
    val activeStage  = in UInt(3 bits)

    // Output flows
    val envelopeOut       = master(Flow(UInt(10 bits)))
    val envelopeOutSigned = master(Flow(SInt(10 bits)))
  }
}
```

### ROM Curves
The shaper houses four distinct compile-time calculated ROMs, each with **257 entries** to safely compute lookup boundary intervals `LUT[x+1]` when `x = 255` without dynamic wrapping checks.

* **Linear ROM:** `LUT[x] = x * (255.0 / 255.0)` (0 to 255)
* **Exponential ROM:** Pre-calculated with decaying inverse-charge capacitor curves.
* **Logarithmic ROM:** Pre-calculated with fast initial rise profiles.
* **S-Curve (Sigmoid) ROM:** Pre-calculated with ease-in/ease-out cosine profiles.

```scala
val linRom = Mem(UInt(8 bits), 257) init(linContent)
val expRom = Mem(UInt(8 bits), 257) init(expContent)
val logRom = Mem(UInt(8 bits), 257) init(logContent)
val sigRom = Mem(UInt(8 bits), 257) init(sigContent)
```

### Multiplierless Hybrid 8+2 Linear Interpolation
Linear interpolation requires the formula: `Y = Y0 + (f / 4) * (Y1 - Y0)`.
To prevent expensive synthesis of physical multipliers on custom silicon, the fraction multiplication `(f/4) * delta_Y` is resolved in combinational shift-add structures based on the 2 fractional bits:

```scala
val y0 = UInt(8 bits)
val y1 = UInt(8 bits)

// Multiplexer select based on curveSelect register
y0 := curveMux(io.curveSelect, io.baseIndex)
y1 := curveMux(io.curveSelect, io.baseIndex + 1)

val delta = y1.asSInt - y0.asSInt
val interp = SInt(10 bits)

switch(io.fraction) {
  is(U"00") { interp := y0.asSInt @@ U"00" } // Y0 * 4
  is(U"01") { interp := (y0.asSInt @@ U"00") + (delta) } // Y0 * 4 + delta
  is(U"10") { interp := (y0.asSInt @@ U"00") + (delta << 1) } // Y0 * 4 + 2 * delta
  is(U"11") { interp := (y0.asSInt @@ U"00") + (delta << 1) + delta } // Y0 * 4 + 3 * delta
}

// Resulting Y is scaled back from 10-bit math down to the unipolar 10-bit range
val finalValUnipolar = (interp >> 2).asUInt
```

#### Parallel Bipolar Output Flow:
* **Unipolar Flow:** `io.envelopeOut.payload := finalValUnipolar`
* **Bipolar Flow:** Bypasses arithmetic blocks by shifting the unipolar range (0 to 1023) to signed (-512 to +511) through direct inversion of the unipolar MSB:
  ```scala
  io.envelopeOutSigned.payload := (finalValUnipolar ^ 0x200).asSInt
  ```
* **Heartbeat Synchronizer:**
  ```scala
  io.envelopeOut.valid       := io.phaseTick
  io.envelopeOutSigned.valid := io.phaseTick
  ```

---

## 6. Signal Flow & Pipeline Timing

To ensure hardware timing closure at 24 MHz and robust, glitch-free control transitions, the Envelope Generator architecture isolates data lookup, mathematical computation, and control registers into dedicated synchronous pipeline stages.

The design implements three distinct hardware signal chains:

```text
               +-------------------------------------------------+
               | 1. Control Input Chain                          |
  syncIn ────> | [2-Stage FF Sync] ──> [FSM Logic] ──> resetReg  |
               +───────────────────────────────────────────┬─────+
                                                           │
                                                           v
               +-------------------------------------------┼-----+
               | 2. Forward Lookup Math Chain              │     |
  24MHz Clk ──>| [Accumulator (T0)] ──> [ROM Lookup (T1)] ─┼──┐  |
               |                                           │  │  |
               |                                           v  │  |
               | [Stage_Delay (T0-T2)] <───────────────────┘  │  |
               |     │ (Match latency)                        │  |
               |     v                                        v  |
               | [Sustain Clamping] <── [Shift-Add Interp (T2)]  |
               |     │                                           |
               |     v                                           |
               | [Output Register (T3)] ──────> envelopeOut      |
               +-------------------------------------------------+
```

---

### 6.1 Chain 1: Control Input Propagation (`syncIn`, `Gate` -> Accumulator)
This path synchronizes asynchronous external controls and propagates internal register signals to steer the accumulator:
* **External Sync Input (`syncIn`):** Connects to a standard 2-stage flip-flop synchronizer clocked at 24 MHz to prevent metastability.
  * *Latency:* **2 clock cycles** (synchronization penalty).
* **Register Settings (`config`):** Synchronous from the `RegisterBank`.
* **State Updates:** The FSM processes inputs combinationally and issues `resetAccum` or `runAccum` control registers to the phase accumulator on the next cycle.
  * *Total Control Latency:* **3 clock cycles** for `syncIn`, **1 clock cycle** for register-driven gate triggers.

---

### 6.2 Chain 2: Forward Lookup Math Pipeline (Accumulator -> Output)
This is the core mathematical flow designed to maintain a 24 MHz clock cycle bound through registered cells:
* **T0 (Accumulation Stage):** The 32-bit register `phase` increments by `phaseInc`. The split base index and fraction bits are output stable.
* **T1 (ROM Lookup Stage):** The 8-bit index addresses the dual boundary ports `LUT[x]` and `LUT[x+1]`. The memory array lookup requires **1 clock cycle** to fetch and settle output registers.
* **T2 (Arithmetic Stage):** The combinational multiplexed shift-add interpolator evaluates `Y = Y0 + (f/4) * delta_Y` instantly.
* **T3 (Output Stage):** To isolate critical routing paths, the final unipolar and bipolar amplitude values are registered to the physical output ports and qualified with `phaseTick`.
  * *Total Forward Latency:* **3 clock cycles** (fully balanced).

---

### 6.3 Chain 3: Sustain Stage Delay Matching (`sustain` -> Output Clamping)
During the `SUSTAIN` state, the envelope holds its output at the static sustain value rather than accumulating phase.
* **The Early Transition Problem:** Because the math pipeline has a **3-cycle latency**, if the FSM immediately switches the output to the static `sustainLevel` value upon entering the `SUSTAIN` state, the output port will jump to the sustain value **3 cycles too early**, truncating the final decay data points still traversing the pipeline!
* **The Solution (Delay Matching):** The active state indicators (like `activeStage` and FSM state controls) are routed through a **3-stage shift register pipeline** (`Stage_Delay`) inside the Shaper. This delays the sustain clamping multiplexer switch by exactly **3 cycles**, matching the lookup pipeline latency and ensuring a perfectly seamless, glitch-free decay-to-sustain transition.


