package synth.oscillator

import spinal.core._
import spinal.lib._

class OscAccumulator extends Component {
  val io = new Bundle {
    val phaseTick = in Bool()
    val freqWord  = in UInt(24 bits)
    val phase     = out UInt(24 bits)
  }

  // The phase register: initialized to 0 on reset.
  val phaseReg = Reg(UInt(24 bits)) init(0)

  // Update logic: The accumulator only advances when the phaseTick is active (480 kHz).
  // It wraps naturally on overflow due to the nature of UInt fixed-width arithmetic.
  when(io.phaseTick) {
    phaseReg := phaseReg + io.freqWord
  }

  // Output the current phase to the Generator submodules.
  io.phase := phaseReg
}
