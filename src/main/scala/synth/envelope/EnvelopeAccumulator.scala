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

  // Default stub assignments
  io.segmentDone := False
  io.baseIndex   := 0
  io.fraction    := 0
}
