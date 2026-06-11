package synth

import spinal.core._
import spinal.core.sim._
import spinal.lib._

import synth.uart._
import synth.output._
import synth.timing.TimingGenerator
import synth.common._
import synth.voice.Voice
import synth.mixing.Mixer

class Synth(numVoices: Int = 1) extends Component {

  val io = new Bundle {
    val clk24MHz = in Bool()
    val reset    = in Bool()

    val uartRx   = in Bool()

    val i2sBclk  = out Bool()
    val i2sLrclk = out Bool()
    val i2sData  = out Bool()
  }

  // Map the external clk and reset pins to the internal ClockDomain logic.
  val coreClockDomain = ClockDomain(
    clock = io.clk24MHz,
    reset = io.reset,
    config = ClockDomainConfig(
      resetKind = ASYNC,
      resetActiveLevel = HIGH
    )
  )

  // System Integration Area
  val core = new ClockingArea(coreClockDomain) {

    // --- Modules ---
    val uart              = new Uart(numVoices)
    val timingGen         = new TimingGenerator()
    val voices            = Seq.tabulate(numVoices)(v => new Voice())
    val mixer             = new Mixer(numVoices)
    val decimator         = new Decimator()
    val transmitter       = new I2STransmitter()

    // Backward compatibility alias for single-voice simulation tests (e.g. SynthSim)
    val voice             = voices(0)

    // --- Simulation hooks
    decimator.io.sampleIn.valid.simPublic()
    decimator.io.sampleIn.payload.simPublic()

    // ------ 0. Synth config & Mixer Hookups
    val mixerCtrl                  = uart.io.synthConfig.mixerCtrl
    mixer.io.mixerCtrl             := mixerCtrl
    mixer.io.phaseTick             := timingGen.io.phaseTick

    // ------ 1. Tick/Sync Distribution & Routing Loop
    for (v <- 0 until numVoices) {
      voices(v).io.phaseTick       := timingGen.io.phaseTick
      voices(v).io.syncIn          := False
      voices(v).io.config          := uart.io.voiceConfig(v)
      mixer.io.inputs(v)           := voices(v).io.sampleOut
    }

    decimator.io.sampleTick        := timingGen.io.sampleTick

    // ------ 2. Communication
    uart.io.rx                     := io.uartRx

    // ------ 3. Audio Data Path
    mixer.io.sampleOut             >> decimator.io.sampleIn
    decimator.io.sampleOut         >> transmitter.io.sampleIn
    
    // ------ 4. Output I2S
    io.i2sBclk                     := transmitter.io.bclk
    io.i2sLrclk                    := transmitter.io.lrclk
    io.i2sData                     := transmitter.io.sdata
  }
}
