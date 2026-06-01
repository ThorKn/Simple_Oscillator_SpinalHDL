package synth.envelope

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import synth.common.EnvelopeStage

class EnvelopeAccumulator extends Component {
  val io = new Bundle {
    // Inputs from Control Unit
    val resetAccum   = in Bool()
    val runAccum     = in Bool()
    val accumDir     = in Bool()               // 0 = Forward (Up), 1 = Reverse (Down)
    val phaseInc     = in UInt(22 bits)
    val sustainLevel = in UInt(8 bits)        // 8-bit sustain target for Decay
    val activeStage  = in UInt(3 bits)         // FSM active stage to control targets

    // Outputs
    val segmentDone = out Bool()              // Boundary completion pulse
    val baseIndex   = out UInt(8 bits)        // LUT address (Upper 8 integer bits)
    val fraction    = out UInt(2 bits)        // Interpolation fraction (Lower 2 integer bits)
  }

  // 32-bit phase accumulator register
  val accum = Reg(UInt(32 bits)).init(0).simPublic()

  // Expand addition to 33 bits to safely detect forward boundary overflow
  val phaseIncExt = io.phaseInc.resize(33)
  val accumExt    = accum.resize(33)
  val nextSum     = accumExt + phaseIncExt
  val overflow    = nextSum(32)

  // Underflow occurs when subtracting phaseInc would wrap below 0
  val underflow   = accum < io.phaseInc

  // Counter logic
  when(io.resetAccum) {
    // If transitioning from ATTACK to DECAY, preset accumulator to full scale (0xFFFFFFFF)
    when(io.activeStage === EnvelopeStage.ATTACK && io.segmentDone) {
      accum := 0xFFFFFFFFL
    } otherwise {
      accum := 0
    }
  } elsewhen(io.runAccum) {
    when(!io.accumDir) { // Forward mode
      accum := accum + io.phaseInc
    } otherwise { // Reverse mode
      accum := accum - io.phaseInc
    }
  }

  // Output splitting: bits 31 to 24 for baseIndex, bits 23 to 22 for fraction
  io.baseIndex := accum(31 downto 24)
  io.fraction  := accum(23 downto 22)

  // Boundary targets based on the current active FSM stage:
  // - Attack (Stage 1): Complete on forward accumulator overflow.
  // - Decay (Stage 2): Complete when baseIndex counts down to match or cross below sustainLevel (<= for safety).
  // - Release (Stage 4): Complete on underflow (counts down to 0).
  val isDecayTarget = io.activeStage === EnvelopeStage.DECAY && io.baseIndex <= io.sustainLevel
  val isReleaseTarget = io.activeStage === EnvelopeStage.RELEASE && underflow

  // Boundary completion segmentDone pulse (asserted for exactly 1 cycle when crossing targets)
  val rawSegmentDone = io.runAccum && (
    (io.activeStage === EnvelopeStage.ATTACK && overflow) ||
    isDecayTarget ||
    isReleaseTarget
  )

  // Gate segmentDone combinationally with current active reset status to ensure absolute reset stability
  io.segmentDone := rawSegmentDone && !ClockDomain.current.isResetActive
}

