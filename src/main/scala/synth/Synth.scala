package synth

import spinal.core._
import spinal.core.sim._
import spinal.lib._

import synth.uart._
import synth.output._
import synth.timing.TimingGenerator
import synth.common._
import synth.voice.Voice

class Synth extends Component {

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
    val uart              = new Uart()
    val timingGen         = new TimingGenerator()
    val voice             = new Voice()
    val decimator         = new Decimator()
    val transmitter       = new I2STransmitter()

    // --- Simulation hooks
    decimator.io.sampleIn.valid.simPublic()
    decimator.io.sampleIn.payload.simPublic()

    // ------ 0. Synth config
    val mixerCtrl                  = uart.io.synthConfig.mixerCtrl

    // ------ 1. Tick/Sync Distribution
    voice.io.phaseTick             := timingGen.io.phaseTick
    voice.io.syncIn                := False
    decimator.io.sampleTick        := timingGen.io.sampleTick

    // ------ 2. Communication
    uart.io.rx                     := io.uartRx

    // ------ 3. Configurations
    voice.io.config                := uart.io.voiceConfig(0)

    // ------ 4. Audio Data Path
    voice.io.sampleOut             >> decimator.io.sampleIn
    decimator.io.sampleOut         >> transmitter.io.sampleIn
    
    // ------ 5. Output I2S
    io.i2sBclk                     := transmitter.io.bclk
    io.i2sLrclk                    := transmitter.io.lrclk
    io.i2sData                     := transmitter.io.sdata
  }
}
