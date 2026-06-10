package synth

import spinal.core._
import spinal.core.sim._
import spinal.lib._

import synth.uart._
import synth.oscillator.Oscillator
import synth.envelope.EnvelopeGenerator
import synth.filter.SVF
import synth.output._
import synth.mixing.Attenuator
import synth.timing.TimingGenerator
import synth.common._

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
    val osc               = new Oscillator()
    val envGen            = new EnvelopeGenerator()
    val envAttenuator     = new Attenuator(volumeWidth = 10)
    val attenuator        = new Attenuator()
    val svf               = new SVF()
    val decimator         = new Decimator()
    val transmitter       = new I2STransmitter()

    // --- Simulation hooks
    envAttenuator.io.volume.simPublic()
    attenuator.io.sampleOut.valid.simPublic()
    attenuator.io.sampleOut.payload.simPublic()
    decimator.io.sampleIn.valid.simPublic()
    decimator.io.sampleIn.payload.simPublic()

    // ------ 1. Tick Distribution
    osc.io.phaseTick               := timingGen.io.phaseTick
    envGen.io.phaseTick            := timingGen.io.phaseTick
    svf.io.phaseTick               := timingGen.io.phaseTick
    envAttenuator.io.phaseTick     := timingGen.io.phaseTick
    attenuator.io.phaseTick        := timingGen.io.phaseTick
    decimator.io.sampleTick        := timingGen.io.sampleTick

    // ------ 2. Sync Distribution
    envGen.io.syncIn               := False

    // ------ 3. Communication
    uart.io.rx                      := io.uartRx

    // ------ 4. Configurations
    osc.io.config                  := uart.io.oscConfig
    envGen.io.config               := uart.io.envConfig
    svf.io.config                  := uart.io.filterConfig

    // ------ 5. Volume
    val envBypassed                = uart.io.envConfig.ctrl(1)
    envAttenuator.io.volume        := envBypassed ? U(1023, 10 bits) | envGen.io.envelopeOut.payload
    attenuator.io.volume           := uart.io.oscConfig.volume

    // ------ 6. Audio Data Path
    osc.io.sample                  >> envAttenuator.io.sampleIn
    envAttenuator.io.sampleOut     >> attenuator.io.sampleIn
    val filterBypassed             = uart.io.filterConfig.ctrl(1) 
    svf.io.sampleIn                := attenuator.io.sampleOut
    decimator.io.sampleIn          := filterBypassed ? attenuator.io.sampleOut | svf.io.sampleOut
    decimator.io.sampleOut         >> transmitter.io.sampleIn
    
    // ------ 7. Output
    io.i2sBclk                    := transmitter.io.bclk
    io.i2sLrclk                   := transmitter.io.lrclk
    io.i2sData                    := transmitter.io.sdata
  }
}
