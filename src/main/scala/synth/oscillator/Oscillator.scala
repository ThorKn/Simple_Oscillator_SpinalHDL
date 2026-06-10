package synth.oscillator

import spinal.core._
import spinal.lib._
import synth.common.OscillatorConfig

class Oscillator extends Component {
  val io = new Bundle {
    val phaseTick = in Bool()
    val config    = in(OscillatorConfig())
    val sample    = master(Flow(SInt(16 bits)))
  }

  // Modules
  val accumulator = new Accumulator()
  val generators  = new Generators()
  val noise       = new Noise()
  val mux         = new Mux()

  // Wire up the Accumulator: Phase advances only on phaseTick (480 kHz)
  accumulator.io.phaseTick := io.phaseTick
  accumulator.io.freqWord  := io.config.freqWord

  // Wire up the Generators: Purely combinational transformation of phase
  generators.io.phase    := accumulator.io.phase
  generators.io.pwmWidth := io.config.pwmWidth

  // Wire up the Noise source: Updates its LFSR state on phaseTick (480 kHz)
  noise.io.phaseTick := io.phaseTick

  // Wire up the Mux for waveform selection
  mux.io.waveSelect := io.config.waveSelect
  mux.io.waves      := generators.io.waves
  mux.io.noiseWave  := noise.io.sample

  // Packaging the output into a Flow. 
  // The sample is valid only during the phaseTick (480 kHz heartbeat).
  io.sample.valid   := io.phaseTick
  io.sample.payload := mux.io.sample
}