package synth.voice

import spinal.core._
import spinal.core.sim._
import spinal.lib._

import synth.oscillator.Oscillator
import synth.envelope.EnvelopeGenerator
import synth.filter.SVF
import synth.mixing.Attenuator
import synth.common._

class Voice extends Component {
  val io = new Bundle {
    val phaseTick    = in Bool()
    val syncIn       = in Bool()
    val config       = in(VoiceConfig())
    val sampleOut    = out(Flow(SInt(16 bits)))
  }

  // --- Submodules ---
  val osc               = new Oscillator()
  val envGen            = new EnvelopeGenerator()
  val envAttenuator     = new Attenuator(volumeWidth = 10)
  val attenuator        = new Attenuator()
  val svf               = new SVF()

  // --- Simulation Hooks ---
  envAttenuator.io.volume.simPublic()
  attenuator.io.sampleOut.valid.simPublic()
  attenuator.io.sampleOut.payload.simPublic()

  // ------ 1. Tick Distribution
  osc.io.phaseTick               := io.phaseTick
  envGen.io.phaseTick            := io.phaseTick
  svf.io.phaseTick               := io.phaseTick
  envAttenuator.io.phaseTick     := io.phaseTick
  attenuator.io.phaseTick        := io.phaseTick

  // ------ 2. Sync Distribution
  envGen.io.syncIn               := io.syncIn

  // ------ 3. Configurations
  osc.io.config                  := io.config.osc
  envGen.io.config               := io.config.env
  svf.io.config                  := io.config.filter

  // ------ 4. Volume
  val envBypassed                = io.config.env.ctrl(1)
  envAttenuator.io.volume        := envBypassed ? U(1023, 10 bits) | envGen.io.envelopeOut.payload
  attenuator.io.volume           := io.config.osc.volume

  // ------ 5. Audio Data Path
  osc.io.sample                  >> envAttenuator.io.sampleIn
  envAttenuator.io.sampleOut     >> attenuator.io.sampleIn
  val filterBypassed             = io.config.filter.ctrl(1) 
  svf.io.sampleIn                := attenuator.io.sampleOut
  io.sampleOut                   := filterBypassed ? attenuator.io.sampleOut | svf.io.sampleOut
}
