package synth.envelope

import spinal.core._
import spinal.lib._
import synth.common.EnvelopeConfig

class EnvelopeGenerator extends Component {
  val io = new Bundle {
    val phaseTick = in Bool()
    val syncIn    = in Bool()
    val midiClock = in Bool()
    val config    = in(EnvelopeConfig())
    val envelopeOut       = master(Flow(UInt(10 bits)))
    val envelopeOutSigned = master(Flow(SInt(10 bits)))
  }

  // Default stub assignments
  io.envelopeOut.valid       := False
  io.envelopeOut.payload     := 0
  io.envelopeOutSigned.valid := False
  io.envelopeOutSigned.payload := 0
}
