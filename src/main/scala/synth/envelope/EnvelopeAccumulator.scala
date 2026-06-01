package synth.envelope

import spinal.core._
import spinal.lib._

class EnvelopeAccumulator extends Component {
  val io = new Bundle {
    val resetAccum  = in Bool()
    val runAccum    = in Bool()
    val accumDir    = in Bool()
    val phaseInc    = in UInt(22 bits)
    val segmentDone = out Bool()
    val baseIndex   = out UInt(8 bits)
    val fraction    = out UInt(2 bits)
  }

  // 32-bit phase accumulator register
  val accum = Reg(UInt(32 bits)) init(0)

  // Expand addition/subtraction to 33 bits to detect overflow/underflow conditions
  val phaseIncExt = io.phaseInc.resize(33)
  val accumExt    = accum.resize(33)

  val nextSum      = accumExt + phaseIncExt
  val overflow     = nextSum(32)

  val nextDiff     = accumExt - phaseIncExt
  val underflow    = accum < io.phaseInc

  // Default segmentDone assignment
  io.segmentDone := False

  when(io.resetAccum) {
    accum := 0
  } elsewhen(io.runAccum) {
    when(!io.accumDir) { // Forward mode
      accum := nextSum(31 downto 0)
      when(overflow) {
        io.segmentDone := True
      }
    } otherwise { // Reverse mode
      accum := nextDiff(31 downto 0)
      when(underflow) {
        io.segmentDone := True
      }
    }
  }

  // Output splitting
  io.baseIndex := accum(31 downto 24)
  io.fraction  := accum(23 downto 22)

  // Ensure segmentDone is strictly held False during active reset
  when(ClockDomain.current.isResetActive) {
    io.segmentDone := False
  }
}
