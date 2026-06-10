package synth.oscillator

import spinal.core._
import spinal.lib._
import synth.common.OscWaveforms

class OscMux extends Component {
  val io = new Bundle {
    val waveSelect = in UInt(3 bits)
    val waves      = in(OscWaveforms())
    val noiseWave  = in SInt(16 bits)
    val sample     = out SInt(16 bits)
  }

  // Combinational selection logic based on the 3-bit waveSelect input.
  // Using a switch statement ensures clarity and priority-free multiplexing.
  switch(io.waveSelect) {
    is(0)   { io.sample := io.waves.saw }
    is(1)   { io.sample := io.waves.square }
    is(2)   { io.sample := io.waves.pwm }
    is(3)   { io.sample := io.waves.tri }
    is(4)   { io.sample := io.noiseWave }
    // Default case for safety: Output silence if an undefined index is selected.
    default { io.sample := S(0, 16 bits) }
  }
}
