package synth.envelope

import spinal.core._
import spinal.lib._

class EnvelopeShaper extends Component {
  val io = new Bundle {
    val phaseTick    = in Bool()
    val baseIndex    = in UInt(8 bits)
    val fraction     = in UInt(2 bits)
    val curveSelect  = in UInt(2 bits)
    val sustainLevel = in UInt(8 bits)
    val activeStage  = in UInt(3 bits)
    val envelopeOut       = master(Flow(UInt(10 bits)))
    val envelopeOutSigned = master(Flow(SInt(10 bits)))
  }

  // Default stub assignments
  io.envelopeOut.valid       := False
  io.envelopeOut.payload     := 0
  io.envelopeOutSigned.valid := False
  io.envelopeOutSigned.payload := 0
}
